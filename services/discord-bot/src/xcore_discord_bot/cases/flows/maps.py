"""Public map review. Stored attachment identity and review hash bind every Accept."""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
import logging
from dataclasses import replace
from datetime import timedelta
from uuid import uuid4

import discord
from xcore_protocol.generated.maps import MapsInspectResponseV1

from ...bus.transport import RpcError
from ...i18n import resolve_dm_locale, t
from ...map_backups import RPC_FAILURES, post_maps_log
from ...map_card import find_map
from ...map_freezes import frozen, frozen_line
from ...map_installs import WRITTEN, post_installs, update_installs
from ...map_list import map_text
from ...map_targets import (
    Incoming,
    Match,
    expectation,
    find_candidates,
    replacement_targets,
    title_key,
)
from ...map_targets import Outcome as Install
from ...map_targets import outcome as install_outcome
from ...retry import TRANSIENT_EXCEPTIONS
from ...rpc.mindustry_rpc import NoLiveServerError
from ...services.map_images import (
    MapImageKind,
    MapRef,
    RendererBusy,
    RendererUnavailable,
    RenderStyle,
)
from ...staff_actions import (
    Actor,
    MapReview,
    MapUploadFile,
    MapUploadTimeout,
    perform_map_upload,
    prepare_map_upload,
)
from ..cards import base_card, safe_text, sanitize_card
from ..model import (
    ActionFailed,
    Case,
    CaseKind,
    CaseRace,
    Origin,
    Party,
    Resolution,
    event,
    utc,
)
from ..msav import InvalidMsav, data_patches, validate_msav
from ..notify import Outcome
from ..registry import Action, Flow, FormField, flow_registry
from ..views import ActionContext
from .badges import grant_map_maker, linked_players

logger = logging.getLogger(__name__)

BADGE_WINDOW = timedelta(days=30)
# A busy renderer is asked again a few times; reviewers can always open the file in game.
# The same default render /map shows, so an accepted map's render is already cached.
REVIEW_STYLE = RenderStyle()
PREVIEW_ATTEMPTS = 3
PREVIEW_RETRY_SECONDS = 20.0
# Renders on their way into a thread, by (thread, file hash): kept so the task is not
# collected and so a recheck while one runs does not start a second.
_previews: dict[tuple[int, str], asyncio.Task[None]] = {}
KNOWN_FINDINGS = frozenset(
    (
        "missing-name",
        "missing-author",
        "requires-mods",
        "data-patches",
        "unknown-content",
        "size-out-of-range",
        "no-core",
        "no-spawn",
        "pvp-single-team",
        "attack-no-enemy-core",
        "td-road-floor-mixed",
        "td-road-floor-unexpected",
        "td-road-disconnected",
        "td-spawn-not-at-road-start",
        "td-border-wall-missing",
        "td-drop-zone-radius",
        "td-waves-enabled",
        "invalid-map",
        "inspection-mismatch",
    )
)
# Findings whose server detail only says the same as the translated text, in English.
PLAIN_FINDINGS = frozenset(
    ("missing-name", "missing-author", "data-patches", "no-spawn", "pvp-single-team")
)


def _belongs(service, thread) -> bool:
    return (
        service.registry.enabled(CaseKind.MAP)
        and getattr(thread, "parent_id", None) == service.settings.discord_maps_forum_id
        and getattr(getattr(thread, "guild", None), "id", None)
        == service.settings.discord_guild_id
    )


def _ignored(service, thread) -> bool:
    ignored = {v.strip() for v in service.settings.maps_ignored_tags.split(",")}
    return any(tag.name in ignored for tag in thread.applied_tags)


def _profile(service, thread) -> str | None:
    profiles = {}
    for pair in service.settings.maps_tag_profiles.split(","):
        name, sep, profile = pair.partition("=")
        if sep and name.strip() and profile.strip():
            profiles[name.strip()] = profile.strip()
    return next(
        (profiles[tag.name] for tag in thread.applied_tags if tag.name in profiles),
        None,
    )


def _file(message):
    return next(
        (
            a
            for a in reversed(message.attachments)
            if a.filename.lower().endswith(".msav")
        ),
        None,
    )


def _identity(message, attachment) -> dict:
    return {
        "message_id": str(message.id),
        "attachment_id": str(attachment.id),
        "file_name": attachment.filename,
        "size": attachment.size,
    }


def _unknown(case: Case) -> bool:
    return case.payload.get("upload_attempt", {}).get("state") == "unknown"


async def _starter(thread):
    for attempt in range(3):
        try:
            return await thread.fetch_message(thread.id)
        except discord.NotFound:
            if attempt == 2:
                raise
            await asyncio.sleep(0.25)


async def _latest(thread):
    # A long discussion must not make a newer author file disappear from selection.
    async for message in thread.history(limit=None):
        if (
            str(message.author.id) == str(thread.owner_id)
            and not message.author.bot
            and _file(message)
        ):
            return message
    message = await _starter(thread)
    return (
        message
        if str(message.author.id) == str(thread.owner_id) and _file(message)
        else None
    )


async def _request_file(service, thread, locale):
    # Before a case exists there is no projection to own this prompt. Adopt a sent prompt
    # after a crash, just as the core adopts a review card by its footer.
    content = t(locale, "maps-file-needed")
    if not await service.threads._has_content(thread, content):
        await thread.send(content, allowed_mentions=discord.AllowedMentions.none())


def _order(case: Case) -> tuple[int, int]:
    return int(case.payload["message_id"]), int(case.payload["attachment_id"])


def _revision(case: Case) -> int:
    return int(case.payload.get("revision") or 1)


def _installed_after(case: Case) -> dict | None:
    """Where this post's map is installed once ``case`` is closed: the server and file of
    its accepted upload, else what it was before."""
    if (
        case.resolution
        and case.resolution.code == "accepted"
        and case.resolution.server
    ):
        return {
            "server": case.resolution.server,
            "file_name": case.resolution.file_name,
            "case": case.number,
            "revision": _revision(case),
            "sha256": case.payload.get("sha256"),
        }
    return case.payload.get("installed")


def _summary(case: Case) -> dict:
    """What the next revision's card compares itself with."""
    info = case.payload.get("info", {})
    patches = next(
        (
            f.get("count") or 1
            for f in case.payload.get("findings", [])
            if f.get("code") == "data-patches"
        ),
        0,
    )
    return {
        "case": case.number,
        "revision": _revision(case),
        "status": case.status.value,
        "sha256": case.payload.get("sha256"),
        "size": case.payload.get("size"),
        "width": info.get("width"),
        "height": info.get("height"),
        "cores": info.get("coresByTeam"),
        "patches": patches,
    }


async def _open_review(
    service, thread, message, attachment, author, *, previous, installed
):
    """Open the review of one file: the post's first, or a later revision of it."""
    locale = await resolve_dm_locale(thread.owner_id, service.store)
    payload = {
        **_identity(message, attachment),
        "profile": _profile(service, thread),
        "creator_handle": getattr(author, "name", None),
        "review_stamp": uuid4().hex,
    }
    submission = f"map:{thread.id}"
    if previous is not None:
        payload.update(
            revision=_revision(previous) + 1,
            previous=_summary(previous),
        )
        submission += f":{attachment.id}"
    if installed:
        payload["installed"] = installed
    return await service.open(
        kind=CaseKind.MAP,
        origin=Origin("forum", submission, locale),
        opener=Party(discord_id=str(thread.owner_id), discord_name=author.display_name),
        payload=payload,
        dedup_key=f"map:{thread.id}",
        flags=("deep_checks_pending",),
        discord={"forum_id": str(thread.parent_id), "thread_id": str(thread.id)},
        member=author if isinstance(author, discord.Member) else None,
    )


async def _author(service, thread, message):
    if message is not None:
        return message.author
    return service.bot.get_user(thread.owner_id) or await service.bot.fetch_user(
        thread.owner_id
    )


async def intake(
    service, thread, *, manual: bool = False, message=None, installed=None
) -> Case | None:
    """Start the review of the author's file in this post.

    The first file starts the post's first review. While a review is open, newer files
    replace its file (``on_message``). Once it is closed, a newer file opens the next
    revision: a review of its own, which knows where the earlier version is installed.
    ``installed`` (``/maps review``) says which installed map this post updates, for maps
    that were put on a server before their post was reviewed.
    """
    if not _belongs(service, thread) or _ignored(service, thread):
        return None
    async with service._lock(f"map-intake:{thread.id}"):
        cases = await service.store.map_cases(thread.id)
        latest = cases[-1] if cases else None
        if latest is not None and latest.active:
            if installed and latest.payload.get("installed") != installed:
                await service.checkpoint(
                    latest.number, set_fields={"payload.installed": installed}
                )
            return await service.resume_creation(latest.number)
        markers = service.store.database["case_map_intake"]
        marker = await markers.find_one({"_id": str(thread.id)})
        # Gateway events also announce old posts when the bot gains access to them. A new
        # file from the author is not such an announcement: it is how a map is updated.
        if (
            latest is None
            and not manual
            and message is None
            and marker is None
            and (
                thread.created_at is None
                or utc(thread.created_at)
                < utc(getattr(service, "maps_enabled_at", service.started_at))
            )
        ):
            return None
        if marker is None:
            await markers.update_one(
                {"_id": str(thread.id)},
                {
                    "$setOnInsert": {
                        "author_id": str(thread.owner_id),
                        "at": service.now(),
                    }
                },
                upsert=True,
            )
        if message is None:
            message = (
                await _latest(thread)
                if manual or latest is not None
                else await _starter(thread)
            )
        attachment = (
            _file(message)
            if message and str(message.author.id) == str(thread.owner_id)
            else None
        )
        if attachment is None:
            if latest is None:
                locale = await resolve_dm_locale(thread.owner_id, service.store)
                await _request_file(service, thread, locale)
            return None
        if latest is not None and (message.id, attachment.id) <= _order(latest):
            return None  # this file was reviewed already
        author = await _author(service, thread, message)
        return await _open_review(
            service,
            thread,
            message,
            attachment,
            author,
            previous=latest,
            installed=installed
            or (_installed_after(latest) if latest is not None else None),
        )


async def on_thread_create(service, thread):
    await intake(service, thread)


async def on_message(service, message):
    thread = message.channel
    if (
        not _belongs(service, thread)
        or message.author.bot
        or str(message.author.id) != str(thread.owner_id)
        or not _file(message)
        or _ignored(service, thread)
    ):
        return
    case = await service.store.by_thread(thread.id)
    if case is None or case.kind != CaseKind.MAP or not case.active:
        # No review yet, or the last one is closed: this file starts the next one. A review
        # that another event was opening meanwhile takes the file below instead.
        await intake(service, thread, message=message)
        case = await service.store.by_thread(thread.id)
        if case is None or case.kind != CaseKind.MAP or not case.active:
            return
    async with service._lock(f"map-file:{case.number}"):
        case = await service.get(case.number)
        if not case.actionable or case.pending_action or _unknown(case):
            return
        attachment = _file(message)
        if (message.id, attachment.id) <= _order(case):
            return
        await _refresh(service, case, message=message)
    await service.project(case.number)


async def _attachment(service, case, *, locale=None):
    thread = await service.threads.channel(case.discord["thread_id"])
    message = await thread.fetch_message(int(case.payload["message_id"]))
    attachment = next(
        (a for a in message.attachments if str(a.id) == case.payload["attachment_id"]),
        None,
    )
    if (
        str(message.author.id) != case.opener.discord_id
        or str(thread.owner_id) != case.opener.discord_id
        or attachment is None
        or _identity(message, attachment)
        != {
            key: case.payload[key]
            for key in ("message_id", "attachment_id", "file_name", "size")
        }
    ):
        raise ActionFailed(t(locale or case.origin.locale, "maps-file-changed"))
    return attachment


async def _refresh(service, case, *, message=None):  # noqa: C901
    # All writes use the revision actually inspected. A concurrent Accept or replacement
    # wins rather than receiving stale results from a slow inspection.
    payload = dict(case.payload)
    thread = await service.threads.channel(case.discord["thread_id"])
    if message is not None:
        identity = _identity(message, _file(message))
        if any(payload.get(key) != value for key, value in identity.items()):
            for key in (
                "server",
                "target_file",
                "changes_requested",
                "upload_attempt",
                "last_upload",
                "badge_state",
                "pending_badge",
            ):
                payload.pop(key, None)
        payload.update(identity)
    payload.update(
        profile=_profile(service, thread),
        findings=[],
        info={},
        validation_error=None,
        inspection_mismatch=False,
        checked_at=service.now(),
        review_stamp=uuid4().hex,
    )
    payload.pop("sha256", None)
    selected = Case.from_doc({**case.to_doc(), "payload": payload})
    pending = False
    try:
        attachment = await _attachment(service, selected)
        if attachment.size > service.settings.maps_max_file_bytes:
            raise InvalidMsav("size")
        data = await attachment.read()
        if len(data) != attachment.size:
            raise InvalidMsav("size")
        local = await asyncio.to_thread(
            validate_msav,
            data,
            attachment.filename,
            max_file_bytes=service.settings.maps_max_file_bytes,
        )
        await _post_patches(service, thread, case, data, local, attachment.filename)
        _schedule_preview(service, thread, case, data, local, attachment.filename)
        payload.update(
            sha256=local.sha256,
            info={
                "name": local.meta.get("name")
                or local.meta.get("mapname")
                or attachment.filename,
                "saveVersion": local.version,
            },
        )
        try:
            raw = await service.transport.rpc_inspect_map(
                url=attachment.url,
                file_name=attachment.filename,
                profile=payload["profile"],
            )
            response = (
                MapsInspectResponseV1.from_payload(raw) if raw is not None else None
            )
        except (
            NoLiveServerError,
            RpcError,
            TimeoutError,
            *TRANSIENT_EXCEPTIONS,
            ValueError,
            TypeError,
        ):
            response = None
        if response is None or response.status.value == "download_failed":
            pending = True
        elif response.status.value == "invalid_map" and response.info is None:
            # A final answer: it carries no info, so the profile check below would retry forever.
            payload["findings"] = [
                {
                    "code": "invalid-map",
                    "severity": "error",
                    "detail": response.detail,
                }
            ]
        elif payload["profile"] is not None and (
            response.info is None or response.info.profile != payload["profile"]
        ):
            pending = True
        else:
            info = response.info
            if info is not None and (
                info.sha256.lower() != local.sha256 or info.sizeBytes != local.size
            ):
                pending = True
                payload["inspection_mismatch"] = True
                payload["findings"] = [
                    {"code": "inspection-mismatch", "severity": "error"}
                ]
            elif response.status.value == "invalid_map":
                payload["findings"] = [{"code": "invalid-map", "severity": "error"}]
            elif info is None:
                pending = True
            else:
                payload["info"] = info.to_payload()
                payload["findings"] = [f.to_payload() for f in response.findings]
    except InvalidMsav as error:
        payload["validation_error"] = error.code
    except (discord.HTTPException, ActionFailed):
        # CDN/message problems remain retryable; no old hash/findings survive.
        pending = True
        payload["validation_error"] = "unavailable"
    flags = [f for f in case.flags if f != "deep_checks_pending"]
    if pending:
        flags.append("deep_checks_pending")
    return await service.store.cas(
        case,
        set_fields={"payload": payload, "flags": flags},
        event=event("evidence_refreshed", None, service.now()),
    )


async def _post_patches(service, thread, case, data, local, filename) -> None:
    """Attach the map's data patches as JSON once per file, so reviewers can read them."""
    try:
        patches = await asyncio.to_thread(data_patches, data)
    except InvalidMsav:
        return
    if not patches:
        return
    document = {}
    for path, text in patches.items():
        try:
            document[path] = json.loads(text) if text is not None else None
        except ValueError:
            document[path] = text  # hjson and the like stay as text
    raw = json.dumps(document, ensure_ascii=False, indent=2).encode()
    content = t(
        case.origin.locale,
        "maps-patches-attached",
        count=len(patches),
        sha=local.sha256[:12],
    )
    try:
        # shortcut: files over 8 MB are not attached; split them if maps ever carry that much.
        if len(raw) > 8_000_000 or await service.threads._has_content(thread, content):
            return
        await thread.send(
            content,
            file=discord.File(
                io.BytesIO(raw), filename=filename.rsplit(".", 1)[0] + "-patches.json"
            ),
            allowed_mentions=discord.AllowedMentions.none(),
        )
    except discord.HTTPException:
        logger.warning("Could not attach data patches to case %s", case.number)


def _schedule_preview(service, thread, case, data, local, filename) -> None:
    """Posts the render of the file under the card, once per file, without holding up checks."""
    key = (thread.id, local.sha256)
    if key in _previews:
        return
    task = asyncio.create_task(
        _post_preview(service, thread, case, data, local, filename),
        name=f"map-preview-{case.number}",
    )
    _previews[key] = task
    task.add_done_callback(lambda _: _previews.pop(key, None))


async def _render_patiently(images, ref, data):
    """The render, asking a busy renderer again; ``None`` when it stays busy."""
    for attempt in range(PREVIEW_ATTEMPTS):
        try:
            return await images.render_file(ref, data, REVIEW_STYLE)
        except RendererBusy:
            if attempt + 1 < PREVIEW_ATTEMPTS:
                await asyncio.sleep(PREVIEW_RETRY_SECONDS)
    return None


async def _post_preview(service, thread, case, data, local, filename) -> None:
    images = service.bot.container.map_images
    content = t(case.origin.locale, "maps-preview-attached", sha=local.sha256[:12])
    ref = MapRef("review", filename)
    try:
        if getattr(thread, "archived", False) or not await images.available(
            MapImageKind.DETAILED
        ):
            return
        if await service.threads._has_content(thread, content):
            return
        image = await _render_patiently(images, ref, data)
        if image is None:
            return
        # A review closed meanwhile keeps its thread as it was left.
        current = await service.get(case.number)
        if current is None or not current.actionable:
            return
        if image.render_ms is not None:
            took = t(
                case.origin.locale, "maps-render-time", seconds=image.render_ms / 1000
            )
            content += f"\n-# {took}"
        await thread.send(
            content,
            file=discord.File(
                io.BytesIO(image.png),
                filename=image.attachment_name(
                    ref, MapImageKind.DETAILED, REVIEW_STYLE
                ),
            ),
            allowed_mentions=discord.AllowedMentions.none(),
        )
    except (TimeoutError, RendererUnavailable) as error:
        logger.info("No render for map case %s: %r", case.number, error)
    except discord.HTTPException:
        logger.warning("Could not attach the render to map case %s", case.number)
    except Exception:
        logger.exception("Render for map case %s failed", case.number)


async def on_created(service, case):
    async with service._lock(f"map-file:{case.number}"):
        await _refresh(service, await service.get(case.number))


async def recheck(context: ActionContext):
    async with context.service._lock(f"map-file:{context.case.number}"):
        case = await context.service.get(context.case.number)
        await context.service.authorize(case, context.member, "recheck")
        if not case.actionable or case.pending_action:
            raise CaseRace()
        if _unknown(case):
            raise ActionFailed(t(context.locale, "maps-upload-unknown"))
        thread = await context.service.threads.channel(case.discord["thread_id"])
        message = await _latest(thread)
        await _refresh(context.service, case, message=message)
    await context.service.project(case.number)
    return t(context.locale, "maps-rechecked")


async def request_changes(context: ActionContext):
    """Send the map back to its author with what to fix. The review stays open; the
    author's next file is checked again on its own and clears the request."""
    service = context.service
    async with service._lock(f"map-file:{context.case.number}"):
        case = await service.get(context.case.number)
        await service.authorize(case, context.member, "changes")
        if not case.actionable or case.pending_action:
            raise CaseRace()
        reason = context.values.get("reason_text", "").strip()
        await service.checkpoint(
            case.number,
            set_fields={
                "payload.changes_requested": {
                    "reason": reason,
                    "by_discord_id": str(context.member.id),
                    "by_name": getattr(context.member, "display_name", "") or "",
                    "at": service.now(),
                }
            },
        )
        thread = await service.threads.channel(case.discord["thread_id"])
        author = case.opener.discord_id
        await thread.send(
            t(
                case.origin.locale,
                "maps-changes-requested-note",
                user=f"<@{author}>",
                reason=safe_text(reason, limit=1500),
            ),
            allowed_mentions=discord.AllowedMentions(
                everyone=False,
                roles=False,
                users=[discord.Object(int(author))] if author else False,
            ),
        )
    await service.project(case.number)
    return t(context.locale, "maps-changes-requested")


def _ready(case, locale):
    if _unknown(case):
        raise ActionFailed(t(locale, "maps-upload-unknown"))
    if (
        case.payload.get("validation_error")
        or not case.payload.get("sha256")
        or case.payload.get("inspection_mismatch")
    ):
        raise ActionFailed(t(locale, "maps-not-valid"))


def _target_name(case, values) -> str:
    """The name the map gets on the server: its own, or the name of the map it replaces."""
    target = values.get("target", "as-is")
    return (
        target.removeprefix("replace:")
        if target.startswith("replace:")
        else case.payload["file_name"]
    )


def _target_option(locale, candidate) -> discord.SelectOption | None:
    value = "replace:" + candidate.file_name
    if len(value) > 100:
        return None
    reasons = ", ".join(
        t(locale, "maps-match-" + match.value)
        for match in (Match.SAME_BYTES, Match.SAME_FILE, Match.SAME_TITLE)
        if match in candidate.reasons
    )
    return discord.SelectOption(
        label=safe_text(
            t(locale, "maps-target-replace", file=candidate.file_name), limit=100
        ),
        value=value,
        description=safe_text(reasons, limit=100) or None,
    )


def _own_installed(case: Case, server: str, maps: list[dict[str, str]]) -> str | None:
    """The installed file that holds this post's earlier version on ``server``, if it is
    still there and still that map: an update replaces it without a question."""
    installed = case.payload.get("installed") or {}
    if installed.get("server") != server or not installed.get("file_name"):
        return None
    item = find_map(maps, installed["file_name"])
    if item is None:
        return None
    info = case.payload.get("info", {})
    same_bytes = (
        bool(installed.get("sha256"))
        and str(item.get("content_hash", "")).lower()
        == str(installed["sha256"]).lower()
    )
    same_map = title_key(info.get("name"), info.get("author")) == title_key(
        item.get("name"), item.get("author")
    )
    # A reviewer who bound the post to a file vouched for it; there is no hash to compare.
    vouched = not installed.get("sha256") and not installed.get("case")
    return item["file_name"] if same_bytes or same_map or vouched else None


async def _expect(context: ActionContext, server: str, target_name: str) -> None:
    """Records what the server must still hold when the upload arrives: the file the
    reviewer saw under that name, or none. A server that changed meanwhile answers
    ``conflict`` instead of overwriting something nobody reviewed."""
    if "expect" in context.values:
        return
    try:
        maps = await context.service.bot.container.rpc.list_maps(
            server=server, timeout_ms=context.service.settings.rpc_timeout_ms
        )
    except RPC_FAILURES:
        maps = None
    expected, absent = expectation(maps, target_name)
    context.values["expect"] = (
        "absent" if absent else f"hash:{expected}" if expected else "none"
    )


def _expected_file(values) -> dict:
    expect = values.get("expect", "none")
    if expect == "absent":
        return {"expect_absent": True}
    if expect.startswith("hash:"):
        return {"expected_hash": expect.removeprefix("hash:")}
    return {}


async def _choose_creator(
    context: ActionContext, case: Case, server: str, target_name: str
) -> bool:
    """Who is recorded as the map's creator; ``True`` when a person was asked.

    A new map, or a new version of this post's own map, is the post author's. A file that
    replaces a map someone else created is a fix: the creator stays, and the post's
    author is recorded as the one who fixed it. A replaced map without a recorded creator
    leaves the choice to the reviewer.
    """
    values = context.values
    if "creator" in values:
        return False
    installed = case.payload.get("installed") or {}
    own = installed.get("case") and installed.get("file_name") == target_name
    if values.get("target", "as-is") == "as-is" or own:
        values["creator"] = "author"
        return False
    try:
        maps = await context.service.bot.container.rpc.list_maps(
            server=server, timeout_ms=context.service.settings.rpc_timeout_ms
        )
    except RPC_FAILURES:
        maps = []
    item = find_map(maps, target_name) or {}
    creator_id = item.get("creator_discord_id")
    if creator_id == case.opener.discord_id:
        values["creator"] = "author"
        return False
    if creator_id:
        values.update(
            creator="original",
            creator_id=creator_id,
            creator_name=item.get("creator_name", ""),
            creator_handle=item.get("creator_handle", ""),
        )
        return False
    file = "`" + map_text(target_name, 100, inline_code=True) + "`"
    await context.choose(
        t(context.locale, "maps-creator-choose", file=file),
        "creator",
        [
            discord.SelectOption(
                label=safe_text(t(context.locale, "maps-creator-author"), limit=100),
                value="author",
            ),
            discord.SelectOption(
                label=safe_text(t(context.locale, "maps-creator-fix"), limit=100),
                value="unknown",
            ),
        ],
    )
    return True


async def _choose_target(context: ActionContext, case: Case, server: str) -> bool:
    """Decides what the map replaces on ``server``; ``True`` when a person was asked.

    A map with nothing like it installed, or that replaces only its own earlier version under
    the same name, needs no question. One that looks like another installed map, or would
    overwrite a different map, waits for the reviewer's choice.
    """
    if "target" in context.values:
        return False
    locale, file_name = context.locale, case.payload["file_name"]
    try:
        maps = await context.service.bot.container.rpc.list_maps(
            server=server, timeout_ms=context.service.settings.rpc_timeout_ms
        )
    except RPC_FAILURES:
        if context.values.get("unlisted") != "yes":
            await context.confirm(
                t(
                    locale,
                    "maps-target-unlisted",
                    server=map_text(server, inline_code=True),
                    file=map_text(file_name, 100, inline_code=True),
                ),
                name="unlisted",
            )
            return True
        context.values["target"] = "as-is"
        return False
    own = _own_installed(case, server, maps)
    if own is not None:
        context.values["target"] = "replace:" + own
        return False
    info = case.payload.get("info", {})
    candidates = find_candidates(
        maps,
        Incoming(
            file_name, case.payload.get("sha256"), info.get("name"), info.get("author")
        ),
    )
    if install_outcome(candidates, file_name) != Install.CONFIRM:
        context.values["target"] = "as-is"
        return False
    by_name = {c.file_name: c for c in candidates}
    options = [
        option
        for name in replacement_targets(candidates, file_name)[:24]
        if (option := _target_option(locale, by_name[name])) is not None
    ]
    overwrites = any(
        c.overwritten_foreign
        for c in candidates
        if c.file_name.casefold() == file_name.casefold()
    )
    options.append(
        discord.SelectOption(
            label=safe_text(
                t(
                    locale,
                    "maps-target-overwrite" if overwrites else "maps-target-new",
                    file=file_name,
                ),
                limit=100,
            ),
            value="as-is",
        )
    )
    lines = [t(locale, "maps-target-choose", server=map_text(server, inline_code=True))]
    for candidate in candidates[:10]:
        item = candidate.item
        lines.append(
            "• "
            + t(
                locale,
                "maps-candidate",
                file="`" + map_text(candidate.file_name, 100, inline_code=True) + "`",
                name=map_text(safe_text(item.get("name"), limit=60) or "?", 60),
                author=map_text(safe_text(item.get("author"), limit=40) or "?", 40),
                reasons=", ".join(
                    t(locale, "maps-match-" + match.value)
                    for match in (Match.SAME_BYTES, Match.SAME_FILE, Match.SAME_TITLE)
                    if match in candidate.reasons
                ),
            )
        )
    await context.choose("\n".join(lines)[:2000], "target", options)
    return True


async def accept(context: ActionContext):  # noqa: C901
    case = context.case
    _ready(case, context.locale)
    stamp = context.values.setdefault("review_stamp", case.payload["review_stamp"])
    if stamp != case.payload["review_stamp"]:
        raise ActionFailed(t(context.locale, "maps-review-changed"))
    profile = case.payload.get("profile") or case.payload.get("info", {}).get("profile")
    servers = sorted(
        context.service.bot.container.servers.get_all_servers(),
        key=lambda s: (s.mode != profile, s.name),
    )
    if not servers:
        raise ActionFailed(t(context.locale, "maps-no-server"))
    installed = case.payload.get("installed") or {}
    if installed.get("server") in {s.name for s in servers}:
        # An update goes where the earlier version is, unless the reviewer picks another.
        servers.sort(key=lambda s: s.name != installed["server"])
    if "server" not in context.values:
        options = []
        for s in servers[:25]:
            label = s.name
            if s.name == installed.get("server"):
                label = t(
                    context.locale,
                    "maps-server-installed",
                    server=s.name,
                    file=installed.get("file_name") or "",
                )
            options.append(
                discord.SelectOption(label=safe_text(label, limit=100), value=s.name)
            )
        await context.choose(
            t(
                context.locale,
                "maps-select-server-update" if installed else "maps-select-server",
            ),
            "server",
            options,
        )
        return None
    server = context.values["server"]
    if server not in {s.name for s in servers}:
        raise ActionFailed(t(context.locale, "maps-no-server"))
    if await _choose_target(context, case, server):
        return None
    target_name = _target_name(case, context.values)
    if (
        not target_name.lower().endswith(".msav")
        or "/" in target_name
        or "\\" in target_name
    ):
        raise ActionFailed(t(context.locale, "maps-review-changed"))
    if await _choose_creator(context, case, server, target_name):
        return None
    await _expect(context, server, target_name)
    if await _choose_others(context, case, server, servers):
        return None
    has_errors = any(f["severity"] == "error" for f in case.payload.get("findings", []))
    freeze = await frozen(context.service.bot, server)
    warnings = [
        t(context.locale, key)
        for key, applies in (
            ("maps-accept-anyway", has_errors),
            ("maps-accept-pending", "deep_checks_pending" in case.flags),
        )
        if applies
    ] + ([frozen_line(context.locale, freeze)] if freeze else [])
    if warnings and context.values.get("accept_anyway") != "yes":
        # One question for everything that is wrong, with what is about to happen.
        plan = t(
            context.locale,
            "maps-accept-plan",
            file="`" + map_text(target_name, 100, inline_code=True) + "`",
            server=map_text(server, inline_code=True),
        )
        await context.confirm("\n".join([*warnings, plan]), name="accept_anyway")
        return None

    async def upload(claimed):
        _ready(claimed, context.locale)
        if claimed.payload["review_stamp"] != stamp:
            raise ActionFailed(t(context.locale, "maps-review-changed"))
        thread = await context.service.threads.channel(claimed.discord["thread_id"])
        attachment = await _attachment(context.service, claimed, locale=context.locale)
        if (
            hashlib.sha256(await attachment.read()).hexdigest()
            != claimed.payload["sha256"]
        ):
            raise ActionFailed(t(context.locale, "maps-file-changed"))
        author = Actor(
            claimed.opener.discord_id,
            claimed.opener.discord_name or "",
            claimed.payload.get("creator_handle"),
        )
        # A fix of someone else's map keeps its creator; the post's author fixed it.
        mode = context.values.get("creator", "author")
        fixer = (
            {
                "discord_id": claimed.opener.discord_id,
                "name": claimed.opener.discord_name or "",
                "handle": claimed.payload.get("creator_handle"),
            }
            if mode in {"original", "unknown"}
            else None
        )
        creator = (
            Actor(
                context.values["creator_id"],
                context.values.get("creator_name", ""),
                context.values.get("creator_handle") or None,
            )
            if mode == "original"
            else None
            if mode == "unknown"
            else author
        )
        files = [
            MapUploadFile(attachment.url, target_name, **_expected_file(context.values))
        ]
        uploader = Actor(
            str(context.member.id),
            context.member.display_name,
            getattr(context.member, "name", None),
        )
        # Validate the common slash payload before recording an uncertain external action.
        prepare_map_upload(server, files, uploader, creator)
        token = claimed.pending_action["token"]
        await context.service.checkpoint(
            claimed.number,
            action_token=token,
            set_fields={
                "payload.server": server,
                "payload.target_file": target_name,
                "payload.fixer": fixer,
                "payload.upload_attempt": {
                    "state": "unknown",
                    "token": token,
                    "server": server,
                },
            },
            unset_fields=("payload.badge_state",),
        )
        latest = await _latest(thread)
        if latest is None or _identity(latest, _file(latest)) != {
            key: claimed.payload[key]
            for key in ("message_id", "attachment_id", "file_name", "size")
        }:
            # Nothing was sent: release only this pre-dispatch marker so the newer file
            # can be reviewed after the action guard is released.
            await context.service.checkpoint(
                claimed.number,
                action_token=token,
                unset_fields=(
                    "payload.upload_attempt",
                    "payload.server",
                    "payload.target_file",
                ),
            )
            raise ActionFailed(t(context.locale, "maps-review-changed"))
        try:
            result = await perform_map_upload(
                context.service.bot,
                server,
                files,
                uploader,
                creator,
                case_number=claimed.number,
                case_action_token=token,
                request_id=f"map:{claimed.number}:{token}",
                reviewed_sha256=claimed.payload["sha256"],
                review=MapReview(
                    claimed.number,
                    claimed.discord.get("thread_id"),
                    _revision(claimed),
                    Actor(fixer["discord_id"], fixer["name"], fixer.get("handle"))
                    if fixer
                    else None,
                ),
            )
        except MapUploadTimeout as error:
            raise ActionFailed(t(context.locale, "maps-upload-unknown")) from error
        status = result.results[0]
        if status.status.value != "installed":
            await context.service.checkpoint(
                claimed.number,
                action_token=token,
                set_fields={
                    "payload.upload_attempt.state": "result",
                    "payload.last_upload": {
                        "server": server,
                        "file_name": status.fileName,
                        "status": status.status.value,
                        "detail": status.detail or "",
                    },
                },
            )
            raise ActionFailed(
                t(
                    context.locale,
                    "maps-upload-result",
                    file=safe_text(status.fileName),
                    status=t(
                        context.locale, "maps-upload-status-" + status.status.value
                    ),
                    detail=safe_text(status.detail),
                )
            )
        await context.service.checkpoint(
            claimed.number,
            action_token=token,
            set_fields={
                "payload.upload_attempt.state": "installed",
                "payload.pending_badge": fixer is None and not _is_update(claimed),
            },
            unset_fields=("payload.last_upload",),
        )
        staff = context.service.settings.cases_staff_locale
        await post_maps_log(
            context.service.bot,
            t(
                staff,
                "maps-log-accepted",
                user=f"<@{context.member.id}>",
                number=claimed.number,
                file="`" + map_text(status.fileName, 100, inline_code=True) + "`",
                server=map_text(server, inline_code=True),
            )
            + (
                " · "
                + t(
                    staff,
                    "maps-upload-note-replaced",
                    source="`"
                    + map_text(attachment.filename, 100, inline_code=True)
                    + "`",
                )
                if target_name != attachment.filename
                else ""
            )
            + f" <#{claimed.discord['thread_id']}>",
        )
        return Resolution(
            "accepted",
            audit_id=result.audit_id,
            server=server,
            file_name=status.fileName,
        )

    try:
        closed = await context.run(upload)
    finally:
        current = await context.service.get(case.number)
        if current.actionable and not current.pending_action and not _unknown(current):
            thread = await context.service.threads.channel(current.discord["thread_id"])
            latest = await _latest(thread)
            if latest is not None:
                await on_message(context.service, latest)
        await context.service.project(case.number)
    if context.values.get("others") == "all":
        await _update_others(context, closed, server)
    return closed


async def _choose_others(
    context: ActionContext, case: Case, server: str, servers
) -> bool:
    """Whether the other servers that hold this post's map get the new revision too;
    ``True`` when a person was asked. Nothing is asked when no other server has it."""
    if "others" in context.values:
        return False
    thread_id = case.discord.get("thread_id")
    others = [s.name for s in servers if s.name != server]
    installs = (
        await post_installs(context.service.bot, thread_id, others)
        if thread_id and others
        else []
    )
    if not installs:
        context.values["others"] = "only"
        return False
    names = ", ".join(install.server for install in installs)
    locale = context.locale
    await context.choose(
        t(
            locale,
            "maps-others-choose",
            server=map_text(server, inline_code=True),
            servers=map_text(names, 1000, inline_code=True),
        ),
        "others",
        [
            discord.SelectOption(
                label=safe_text(t(locale, "maps-others-all", servers=names), limit=100),
                value="all",
            ),
            discord.SelectOption(
                label=safe_text(
                    t(locale, "maps-others-only", server=server), limit=100
                ),
                value="only",
            ),
        ],
    )
    return True


async def _update_others(context: ActionContext, closed: Case, server: str) -> None:
    """After an accepted update: the same revision over the post's file on every other
    server that holds it. Each server answers on its own; a frozen one is left alone, and
    any that was not written is named with ``/maps install`` to retry."""
    if not closed.resolution or closed.resolution.code != "accepted":
        return
    bot = context.service.bot
    staff = context.service.settings.cases_staff_locale
    try:
        thread = await context.service.threads.channel(closed.discord["thread_id"])
        others = [
            s.name for s in bot.container.servers.get_all_servers() if s.name != server
        ]
        installs = await post_installs(bot, thread.id, others)
        held = {i.server for i in installs if await frozen(bot, i.server) is not None}
        results = await update_installs(
            bot,
            case=closed,
            thread=thread,
            installs=[i for i in installs if i.server not in held],
            uploader=Actor(
                str(context.member.id),
                context.member.display_name,
                getattr(context.member, "name", None),
            ),
        )
        lines = [
            t(
                staff,
                "maps-others-note",
                user=f"<@{context.member.id}>",
                number=closed.number,
            )
        ]
        lines += [
            "• "
            + t(
                staff,
                "maps-others-row",
                server=map_text(name, inline_code=True),
                status=t(staff, "maps-upload-label-" + status),
            )
            for name, status in results
        ]
        lines += [
            "• "
            + t(staff, "maps-others-frozen", server=map_text(name, inline_code=True))
            for name in sorted(held)
        ]
        if any(status not in WRITTEN for _, status in results) or held:
            lines.append(t(staff, "maps-others-retry"))
        text = "\n".join(lines)[:2000]
        await thread.send(text, allowed_mentions=discord.AllowedMentions.none())
        await post_maps_log(bot, text + f" <#{thread.id}>")
    except Exception:  # The accepted upload stands; the others can be updated by hand.
        logger.exception(
            "Could not update the other servers of case #%s", closed.number
        )


async def _badge(service, case):
    if (
        not case.resolution
        or case.resolution.code != "accepted"
        or not case.closed_at
        or not timedelta(0) <= service.now() - case.closed_at <= BADGE_WINDOW
    ):
        return
    await service.checkpoint(case.number, set_fields={"payload.pending_badge": True})
    try:
        result = await grant_map_maker(
            service,
            case.opener.discord_id,
            actor=Actor(case.resolution.by_discord_id, case.resolution.by_name or ""),
            request_id=f"map-maker:{case.number}",
        )
    except Exception as error:  # noqa: BLE001 -- recorded; reconcile retries the pending badge
        await service._note_error(case.number, "map_maker", error)
        return
    await service.checkpoint(
        case.number,
        set_fields={
            "payload.badge_state": result,
        },
        unset_fields=("payload.pending_badge",) if result == "complete" else (),
    )
    if result == "link_needed":
        await service.threads.post_note(
            case, t(case.origin.locale, "maps-badge-link-needed")
        )


def _is_update(case: Case) -> bool:
    """A later revision of a map this post already had accepted."""
    return bool((case.payload.get("installed") or {}).get("case"))


def _earns_badge(case: Case) -> bool:
    """Map Maker is for creating a map: an update was earned by the first accepted
    revision, and a fix of someone else's map is not its creation."""
    return not _is_update(case) and not case.payload.get("fixer")


def _outcome_key(case: Case) -> str:
    if case.resolution is None or case.resolution.code != "accepted":
        return "maps-outcome-rejected"
    if case.payload.get("fixer"):
        return "maps-outcome-fixed"
    return "maps-outcome-updated" if _is_update(case) else "maps-outcome-accepted"


def outcomes(case):
    recipient = case.progress.get("map_outcome", {})
    if recipient.get("closed_at") != case.closed_at:
        return ()
    party = replace(case.opener, player_uuid=recipient.get("player_uuid"))
    secrets = (party.player_uuid or "",)
    accepted = case.resolution.code == "accepted"
    params = (
        {
            "file": safe_text(
                case.resolution.file_name or case.payload.get("file_name"),
                secrets=secrets,
            ),
            "server": safe_text(case.resolution.server, secrets=secrets),
        }
        if accepted
        else {
            "reason": safe_text(
                case.resolution.reason_text or case.resolution.reason_code,
                secrets=secrets,
            )
        }
    )
    params["map"] = safe_text(
        case.payload.get("info", {}).get("name") or case.payload.get("file_name"),
        secrets=secrets,
    )
    return (
        Outcome(
            party,
            "map_accepted" if accepted else "map_rejected",
            _outcome_key(case),
            params,
        ),
    )


async def on_closed(service, case):
    if case.progress.get("map_outcome", {}).get("closed_at") != case.closed_at:
        players = await linked_players(service, case.opener.discord_id)
        case = await service.checkpoint(
            case.number,
            set_fields={
                "progress.map_outcome": {
                    "closed_at": case.closed_at,
                    "player_uuid": players[0].uuid if len(players) == 1 else None,
                }
            },
        )
    if case.resolution.code == "accepted" and _unknown(case):
        case = await service.checkpoint(
            case.number, set_fields={"payload.upload_attempt.state": "installed"}
        )
    if (
        case.resolution.code == "accepted"
        and case.payload.get("badge_state") != "complete"
        and _earns_badge(case)
    ):
        await _badge(service, case)
    outcome = outcomes(case)[0]
    await service.threads.post_note(
        case,
        t(
            case.origin.locale,
            outcome.message_key,
            number=case.number,
            **outcome.params,
        ),
    )


async def reconcile(service, case):
    if (
        case.actionable
        and not case.pending_action
        and not _unknown(case)
        and "deep_checks_pending" in case.flags
    ):
        async with service._lock(f"map-file:{case.number}"):
            current = await service.get(case.number)
            if (
                current.actionable
                and not current.pending_action
                and not _unknown(current)
            ):
                await _refresh(service, current)
        await service.project(case.number)
    elif case.payload.get("pending_badge") and _earns_badge(case):
        if (
            case.resolution
            and case.resolution.code == "accepted"
            and case.closed_at
            and service.now() - case.closed_at <= BADGE_WINDOW
            and not case.progress.get("outcomes_done")
        ):
            await service.finish_close(case.number)
            return
        await _badge(service, case)
        await service.project(case.number)


def _finding(finding, locale):
    code = finding["code"]
    text = (
        t(locale, "maps-finding-" + code)
        if code in KNOWN_FINDINGS
        else safe_text(code, limit=120)
    )
    if finding.get("x") is not None and finding.get("y") is not None:
        text += f" ({finding['x']}, {finding['y']})"
    if finding.get("count") is not None:
        text += " · " + t(locale, "maps-finding-count", count=finding["count"])
    if finding.get("detail") and code not in PLAIN_FINDINGS:
        text += " — " + safe_text(finding["detail"], limit=180)
    return text[:350]


def _cores(value) -> str:
    if not isinstance(value, dict) or not value:
        return "—"
    return ", ".join(f"{team} {count}" for team, count in sorted(value.items()))


def _changes(case: Case, locale: str) -> list[str]:
    """What differs from the previous revision, in what the checks measure."""
    payload, previous = case.payload, case.payload.get("previous") or {}
    info = payload.get("info", {})
    if previous.get("sha256") and previous["sha256"] == payload.get("sha256"):
        return [
            t(locale, "maps-changes-identical", revision=previous.get("revision", 1))
        ]
    lines = []
    if (
        previous.get("size")
        and payload.get("size")
        and previous["size"] != payload["size"]
    ):
        lines.append(
            t(
                locale,
                "maps-changes-size",
                before=previous["size"],
                after=payload["size"],
            )
        )
    before = (previous.get("width"), previous.get("height"))
    after = (info.get("width"), info.get("height"))
    if None not in before and None not in after and before != after:
        lines.append(
            t(
                locale,
                "maps-changes-dimensions",
                before=f"{before[0]} × {before[1]}",
                after=f"{after[0]} × {after[1]}",
            )
        )
    if (
        isinstance(previous.get("cores"), dict)
        and isinstance(info.get("coresByTeam"), dict)
        and previous["cores"] != info["coresByTeam"]
    ):
        lines.append(
            t(
                locale,
                "maps-changes-cores",
                before=safe_text(_cores(previous["cores"]), limit=150),
                after=safe_text(_cores(info["coresByTeam"]), limit=150),
            )
        )
    patches = next(
        (
            f.get("count") or 1
            for f in payload.get("findings", [])
            if f.get("code") == "data-patches"
        ),
        0,
    )
    if payload.get("info") and patches != (previous.get("patches") or 0):
        lines.append(
            t(
                locale,
                "maps-changes-patches",
                before=previous.get("patches") or 0,
                after=patches,
            )
        )
    return lines or [t(locale, "maps-changes-none")]


def _revision_fields(embed, case: Case, locale: str) -> None:
    payload = case.payload
    previous = payload.get("previous")
    if previous:
        embed.add_field(
            name=t(locale, "maps-revision"),
            value=t(
                locale,
                "maps-revision-info",
                revision=_revision(case),
                case=previous.get("case", "?"),
                status=t(locale, "maps-revision-status-" + str(previous.get("status"))),
            ),
            inline=False,
        )
    installed = payload.get("installed")
    if installed and case.active:
        embed.add_field(
            name=t(locale, "maps-installed-now"),
            value=t(
                locale,
                "maps-installed-info",
                server=safe_text(installed.get("server"), limit=100),
                file=safe_text(installed.get("file_name"), limit=150),
            ),
            inline=False,
        )
    if previous and case.active:
        embed.add_field(
            name=t(locale, "maps-changes"),
            value="\n".join(_changes(case, locale))[:1000],
            inline=False,
        )


def card_builder(case, locale):  # noqa: C901
    embed = base_card(case, locale)
    # Bound the free-text fields before allocating space to grouped findings.
    for index, field in enumerate(embed.fields):
        embed.set_field_at(
            index, name=field.name, value=field.value[:300], inline=field.inline
        )
    payload, info = case.payload, case.payload.get("info", {})
    embed.add_field(
        name=t(locale, "maps-map"),
        value=safe_text(info.get("name") or payload.get("file_name"), limit=200) or "…",
        inline=False,
    )
    embed.add_field(
        name=t(locale, "maps-file"),
        value=t(
            locale,
            "maps-file-info",
            file=safe_text(payload.get("file_name"), limit=150),
            size=payload.get("size", 0),
        ),
        inline=False,
    )
    mode = (
        info.get("profile")
        or payload.get("profile")
        or info.get("gameMode")
        or t(locale, "cases-unknown")
    )
    dimensions = (
        t(locale, "maps-dimensions", width=info["width"], height=info["height"])
        if "width" in info and "height" in info
        else ""
    )
    embed.add_field(
        name=t(locale, "maps-mode"),
        value=safe_text(mode, limit=100) + (" · " + dimensions if dimensions else ""),
        inline=False,
    )
    _revision_fields(embed, case, locale)
    requested = payload.get("changes_requested")
    if requested and case.active:
        embed.add_field(
            name=t(locale, "maps-changes-requested-field"),
            value=safe_text(requested.get("reason"), limit=900) or "…",
            inline=False,
        )
    if payload.get("validation_error"):
        embed.add_field(
            name=t(locale, "maps-validation"),
            value=t(locale, "maps-invalid-" + payload["validation_error"]),
            inline=False,
        )
    if "deep_checks_pending" in case.flags:
        embed.add_field(
            name=t(locale, "maps-checks"),
            value=t(locale, "maps-checks-pending"),
            inline=False,
        )
    if _unknown(case) and case.active:
        embed.add_field(
            name=t(locale, "maps-upload"),
            value=t(locale, "maps-upload-unknown"),
            inline=False,
        )
    elif payload.get("last_upload") and case.active:
        result = payload["last_upload"]
        embed.add_field(
            name=t(locale, "maps-upload"),
            value=t(
                locale,
                "maps-upload-result",
                file=safe_text(result["file_name"], limit=150),
                status=t(locale, "maps-upload-status-" + result["status"]),
                detail=safe_text(result["detail"], limit=200),
            ),
            inline=False,
        )
    if case.resolution and case.resolution.code == "accepted":
        embed.add_field(
            name=t(locale, "maps-installed"),
            value=t(
                locale,
                "maps-installed-info",
                server=safe_text(case.resolution.server, limit=100),
                file=safe_text(case.resolution.file_name, limit=150),
            ),
            inline=False,
        )
        if payload.get("fixer"):
            embed.add_field(
                name=t(locale, "maps-fixed-by"),
                value=f"<@{payload['fixer']['discord_id']}>",
                inline=False,
            )
    for severity in ("error", "warning", "info"):
        findings = [f for f in payload.get("findings", []) if f["severity"] == severity]
        if not findings:
            continue
        lines = []
        for finding in findings:
            line = _finding(finding, locale)
            if sum(len(v) + 1 for v in lines) + len(line) > 850:
                break
            lines.append(line)
        if len(lines) < len(findings):
            lines.append(t(locale, "maps-more", count=len(findings) - len(lines)))
        embed.add_field(
            name=t(locale, "maps-severity-" + severity),
            value="\n".join(lines),
            inline=False,
        )
    return sanitize_card(embed, case)


MAP_FLOW = flow_registry.register(
    Flow(
        kind=CaseKind.MAP,
        card_builder=card_builder,
        takeable=False,
        actions=(
            Action(
                "accept",
                "maps-accept",
                accept,
                style=discord.ButtonStyle.success,
                label_for=lambda case: (
                    "maps-update" if case.payload.get("installed") else "maps-accept"
                ),
            ),
            Action(
                "changes",
                "maps-changes-button",
                request_changes,
                fields=(
                    FormField("reason_text", "maps-changes-reason", paragraph=True),
                ),
            ),
            Action("recheck", "maps-recheck", recheck),
        ),
        on_thread_create=on_thread_create,
        on_message=on_message,
        on_created=on_created,
        on_closed=on_closed,
        outcomes=outcomes,
        reconcile=reconcile,
    )
)
