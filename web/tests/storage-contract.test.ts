/**
 * The website's readers against the shared MongoDB contract in protocol/storage.
 *
 * Every stored shape of a players document goes through the real reader functions over an
 * in-memory database that records each field they filter, sort, project or read. Those fields
 * must be declared in the schema as read by the website, so a field renamed in the schema and its
 * fixtures fails here while the site still reads the old name.
 */
import { describe, expect, it } from "bun:test";
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { BSON, type Db, type Document } from "mongodb";

const { getLeaderboard, getLinkedProfiles, getPlayerProfile, searchPlayers } = await import("../src/lib/server/stats");

/* eslint-disable @typescript-eslint/no-explicit-any */
const STORAGE = join(import.meta.dir, "../../protocol/storage");
const schema = JSON.parse(readFileSync(join(STORAGE, "schemas/players.v1.json"), "utf8"));
const readByWeb = new Set(
  Object.entries(schema.properties as Record<string, any>)
    .filter(([, field]) => field["x-read-by"]?.web)
    .map(([name]) => name),
);

function jsonFiles(dir: string): string[] {
  return existsSync(dir) ? readdirSync(dir).filter(f => f.endsWith(".json")).sort().map(f => join(dir, f)) : [];
}

/** Every shape a reader can meet: current and legacy documents, and both sides of each migration. */
function stored(collection: string): Document[] {
  const migrations = join(STORAGE, "migrations");
  const paths = [
    ...jsonFiles(join(STORAGE, "fixtures", collection, "valid")),
    ...jsonFiles(join(STORAGE, "fixtures", collection, "legacy")),
    ...(existsSync(migrations) ? readdirSync(migrations).sort() : []).flatMap(m => [
      ...jsonFiles(join(migrations, m, "before", collection)),
      ...jsonFiles(join(migrations, m, "after", collection)),
    ]),
  ];
  // Relaxed, as the driver hands documents to the site: numbers as numbers, dates as Date.
  return paths.map(path => BSON.EJSON.parse(readFileSync(path, "utf8"), { relaxed: true }) as Document);
}

function value(doc: any, path: string): any {
  return path.split(".").reduce((v, key) => (v == null ? undefined : v[key]), doc);
}

function matches(doc: any, filter: Record<string, any>): boolean {
  return Object.entries(filter).every(([key, want]) => {
    if (key === "$and") return want.every((f: any) => matches(doc, f));
    if (key === "$or") return want.some((f: any) => matches(doc, f));
    const have = value(doc, key);
    if (want instanceof RegExp) return want.test(String(have ?? ""));
    if (want && typeof want === "object" && !(want instanceof Date)) {
      if ("$regex" in want) return new RegExp(want.$regex, want.$options ?? "").test(String(have ?? ""));
      if ("$gt" in want) return have > want.$gt;
      if ("$in" in want) return want.$in.includes(have);
    }
    return have === want;
  });
}

/** Fields a filter, sort or projection names; operators are not fields. */
function named(spec: Record<string, any> | undefined, into: Set<string>) {
  for (const [key, inner] of Object.entries(spec ?? {})) {
    if (key === "$and" || key === "$or") (inner as any[]).forEach(f => named(f, into));
    else if (!key.startsWith("$")) into.add(key.split(".")[0]);
  }
}

/** A players collection that records what it is asked for; every other collection is empty. */
function recordingDb(docs: Document[], seen: Set<string>): Db {
  const reads = (doc: Document, projection?: Record<string, number>) => {
    const keep = projection && Object.entries(projection).filter(([, on]) => on).map(([k]) => k);
    const shown = keep?.length ? Object.fromEntries(keep.filter(k => k in doc).map(k => [k, doc[k]])) : { ...doc };
    return new Proxy(shown, {
      get(target, key) {
        // await looks for then on whatever findOne resolves to.
        if (typeof key === "string" && key !== "then" && !(key in Object.prototype)) seen.add(key);
        return Reflect.get(target, key);
      },
    });
  };
  const sortBy = (rows: Document[], sort?: Record<string, number>) => {
    named(sort, seen);
    return [...rows].sort((a, b) => {
      for (const [key, dir] of Object.entries(sort ?? {})) {
        if (a[key] !== b[key]) return (a[key] > b[key] ? 1 : -1) * dir;
      }
      return 0;
    });
  };
  const players = {
    find(filter: Record<string, any>, options: any = {}) {
      named(filter, seen);
      named(Object.fromEntries(Object.entries(options.projection ?? {}).filter(([, on]) => on)), seen);
      let rows = docs.filter(d => matches(d, filter));
      let skip = 0, limit = Infinity;
      const cursor = {
        sort(spec: Record<string, number>) { rows = sortBy(rows, spec); return cursor; },
        skip(n: number) { skip = n; return cursor; },
        limit(n: number) { limit = n; return cursor; },
        async toArray() { return rows.slice(skip, skip + limit).map(d => reads(d, options.projection)); },
      };
      return cursor;
    },
    async findOne(filter: Record<string, any>, options: any = {}) {
      const rows = await players.find(filter, options).sort(options.sort ?? {}).toArray();
      return rows[0] ?? null;
    },
    async countDocuments(filter: Record<string, any>) {
      named(filter, seen);
      return docs.filter(d => matches(d, filter)).length;
    },
  };
  const empty = {
    find() {
      const cursor: any = { sort: () => cursor, skip: () => cursor, limit: () => cursor, toArray: async () => [] };
      return cursor;
    },
    findOne: async () => null,
    countDocuments: async () => 0,
    distinct: async () => [],
    aggregate: () => ({ toArray: async () => [] }),
  };
  return { collection: (name: string) => (name === "players" ? players : empty) } as unknown as Db;
}

function declaredOnly(seen: Set<string>) {
  const undeclared = [...seen].filter(field => !readByWeb.has(field));
  expect(undeclared, "players fields the site reads that players.v1.json does not declare as read by web").toEqual([]);
}

const iso = (v: unknown) => (typeof v === "number" && v > 0 ? new Date(v).toISOString() : null);

describe("players, as stored", () => {
  const docs = stored("players");

  it("has fixtures", () => {
    expect(docs.length).toBeGreaterThan(0);
  });

  it("profiles show every stored player as stored", async () => {
    const seen = new Set<string>();
    // One document at a time: a migration's before and after fixtures share a pid.
    for (const doc of docs.filter(d => typeof d.pid === "number")) {
      const profile = await getPlayerProfile(doc.pid, recordingDb([doc], seen));
      expect(profile?.nickname).toBe(doc.nickname ?? "Unknown");
      expect(profile?.totalPlayTime).toBe(doc.total_play_time ?? 0);
      expect(profile?.isAdmin).toBe(Boolean(doc.is_admin));
      expect(profile?.createdAt).toBe(iso(doc.created_at));
      expect(profile?.unlockedBadges).toEqual(doc.unlocked_badges ?? []);
    }
    declaredOnly(seen);
  });

  it("search, leaderboards and linked profiles read declared fields", async () => {
    const seen = new Set<string>();
    const db = recordingDb(docs, seen);
    for (const doc of docs) {
      const name = String(doc.nickname ?? "").replace(/\[[^\]]*\]/g, "");
      if (name) expect((await searchPlayers(name, 1, 50, db)).items.some(p => p.pid === doc.pid)).toBe(true);
      if (doc.discord_id) expect((await getLinkedProfiles(doc.discord_id, db)).map(p => p.pid)).toContain(doc.pid);
    }
    const playtime = await getLeaderboard("playtime", 1, undefined, 50, db);
    expect(playtime.items.length).toBe(docs.length);
    await getLeaderboard("hexed", 1, undefined, 50, db);
    declaredOnly(seen);
  });
});
