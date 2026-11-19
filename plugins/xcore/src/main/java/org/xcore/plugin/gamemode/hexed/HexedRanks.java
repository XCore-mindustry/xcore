package org.xcore.plugin.gamemode.hexed;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class HexedRanks {
    /** protocol/storage/hexed-ranks.v1.json, bundled into the jar: the Discord bot reads the same file. */
    static final String POINTS = "/xcore/storage/hexed-ranks.v1.json";

    public enum HexedRank {
        newbie() {},

        regular(newbie) {{
            tag = "[cyan]<[accent]\uF7E7[cyan]>[]";
        }},

        advanced(regular) {{
            tag = "[cyan]<[accent]\uF7ED[cyan]>[]";
        }},

        veteran(advanced) {{
            tag = "[cyan]<[accent]\uF7EC[cyan]>[]";
        }},

        davastator(veteran) {{
            tag = "[cyan]<[accent]\uF7C4[cyan]>[]";
        }},

        the_legend(davastator) {{
            tag = "[cyan]<[accent]\uF7C6[cyan]>[]";
        }};

        static {
            var points = points();
            if (points.length != values().length) {
                throw new IllegalStateException(POINTS + " has " + points.length + " ranks, HexedRank " + values().length);
            }
            for (var rank : values()) rank.requirements = new Requirements(points[rank.ordinal()]);
        }

        public String tag = "";
        public HexedRank next;
        public Requirements requirements;

        HexedRank() {
        }

        HexedRank(HexedRank previous) {
            previous.next = this;
        }

        public boolean hasNext() {
            return next != null && next.requirements != null;
        }

        public boolean checkNext(int wins) {
            return hasNext() && next.requirements.check(wins);
        }

        /** The highest rank the points reach. */
        public static HexedRank of(int points) {
            var current = newbie;
            while (current.checkNext(points)) current = current.next;
            return current;
        }
    }

    public record Requirements(int wins) {
        public boolean check(int wins) {
            return wins >= this.wins;
        }
    }

    private static int[] points() {
        try (var in = HexedRanks.class.getResourceAsStream(POINTS)) {
            if (in == null) throw new IllegalStateException(POINTS + " is missing from the jar");
            var points = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("points");
            var result = new int[points.size()];
            for (int i = 0; i < result.length; i++) result[i] = points.get(i).getAsInt();
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
