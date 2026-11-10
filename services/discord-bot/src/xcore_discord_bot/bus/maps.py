"""Maps: listing, uploading, removing and inspecting maps on a server, and their pictures."""

from __future__ import annotations

import hashlib
import json
import time
from typing import Any, cast

from redis.client import NEVER_DECODE
from xcore_protocol.generated.maps import (
    MapsFileRequestV1,
    MapsFileResponseV1,
    MapsInspectResponseV1,
    MapsListRequestV1,
    MapsListResponseV1,
    MapsPreviewRequestV1,
    MapsPreviewResponseV1,
    MapsRemoveRequestV1,
    MapsRemoveResponseV1,
    MapsRenderRequestV1,
    MapsRenderResponseV1,
    MapsRestoreRequestV1,
    MapsRestoreResponseV1,
    MapsTrashListRequestV1,
    MapsTrashListResponseV1,
    MapsUploadRequestV1,
    MapsUploadResponseV1,
)
from xcore_protocol.generated.shared import (
    MapBlobRefV1,
    MapBlobRefV1ContentType,
    MapEntryV1,
)

from ..protocol_outbound import (
    build_maps_inspect_request,
    build_maps_list_request,
    build_maps_remove_request,
    build_maps_upload_request,
)
from .transport import RedisTransport

MAP_INSPECT_TTL_MS = 60_000
# The map renderer listens on xcore:rpc:req:map-renderer, as if it were a server of that name.
RENDERER = "map-renderer"
# The protocol's ceiling for one blob.
MAX_BLOB_BYTES = 16 * 1024 * 1024
BLOB_PREFIX = "xcore:blob:"
BLOB_TTL_SECONDS = 15 * 60
# Kept by the renderer while it listens (a 30 s TTL refreshed every 10 s).
RENDERER_PRESENCE_KEY = "xcore:service:map-renderer"


class BlobUnavailable(RuntimeError):
    """A referenced blob expired, never arrived, or does not match its reference."""


class MapsBus:
    def __init__(self, transport: RedisTransport) -> None:
        self._transport = transport

    async def rpc_maps_list(self, server: str, timeout_ms: int) -> list[dict[str, str]]:
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=MapsListRequestV1.MESSAGE_TYPE,
            payload=build_maps_list_request(server=server).to_payload(),
            timeout_ms=timeout_ms,
        )

        payload_json = body.get("payload_json", "{}")
        payload = json.loads(payload_json)
        response = MapsListResponseV1.from_payload(payload)
        return [self._normalize_map_entry_v1(entry) for entry in response.maps]

    async def rpc_upload_maps(
        self, *, server: str, files: list[dict[str, object]], timeout_ms: int
    ) -> MapsUploadResponseV1:
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=MapsUploadRequestV1.MESSAGE_TYPE,
            payload=build_maps_upload_request(server, files).to_payload(),
            timeout_ms=timeout_ms,
        )
        response = MapsUploadResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        expected = [item.get("fileName", item.get("filename")) for item in files]
        if (
            response.server != server
            or [result.fileName for result in response.results] != expected
        ):
            raise ValueError(
                "Map upload response does not match the requested files/server"
            )
        return response

    async def rpc_remove_map(
        self,
        server: str,
        file_name: str,
        timeout_ms: int,
        expected_content_hash: str | None = None,
    ) -> MapsRemoveResponseV1:
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=MapsRemoveRequestV1.MESSAGE_TYPE,
            payload=build_maps_remove_request(
                server=server,
                file_name=file_name,
                expected_content_hash=expected_content_hash,
            ).to_payload(),
            timeout_ms=timeout_ms,
        )

        payload_json = body.get("payload_json", "{}")
        payload = json.loads(payload_json)
        return MapsRemoveResponseV1.from_payload(payload)

    async def rpc_maps_trash_list(
        self, *, server: str, timeout_ms: int
    ) -> MapsTrashListResponseV1:
        request = MapsTrashListRequestV1(server=server)
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=request.MESSAGE_TYPE,
            payload=request.to_payload(),
            timeout_ms=timeout_ms,
        )
        response = MapsTrashListResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        if response.server != server:
            raise ValueError("Map trash response does not match the request")
        return response

    async def rpc_maps_restore(
        self, *, server: str, trash_id: str, file_name: str | None, timeout_ms: int
    ) -> MapsRestoreResponseV1:
        request = MapsRestoreRequestV1(
            server=server, trashId=trash_id, fileName=file_name
        )
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=request.MESSAGE_TYPE,
            payload=request.to_payload(),
            timeout_ms=timeout_ms,
        )
        response = MapsRestoreResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        if response.server != server or response.trashId != trash_id:
            raise ValueError("Map restore response does not match the request")
        return response

    async def rpc_inspect_map(
        self, *, server: str, url: str, file_name: str, profile: str | None = None
    ) -> MapsInspectResponseV1:
        request = build_maps_inspect_request(
            server=server, url=url, file_name=file_name, profile=profile
        )
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=request.MESSAGE_TYPE,
            payload=request.to_payload(),
            timeout_ms=MAP_INSPECT_TTL_MS,
        )
        response = MapsInspectResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        if response.server != server:
            raise ValueError("Map inspection response does not match request server")
        return response

    async def rpc_maps_preview(
        self, *, server: str, file_name: str, max_side: int | None, timeout_ms: int
    ) -> MapsPreviewResponseV1:
        request = MapsPreviewRequestV1(
            server=server, fileName=file_name, maxSide=max_side
        )
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=request.MESSAGE_TYPE,
            payload=request.to_payload(),
            timeout_ms=timeout_ms,
        )
        response = MapsPreviewResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        if response.server != server or response.fileName != file_name:
            raise ValueError("Map preview response does not match the request")
        return response

    async def rpc_maps_file(
        self, *, server: str, file_name: str, timeout_ms: int
    ) -> MapsFileResponseV1:
        request = MapsFileRequestV1(server=server, fileName=file_name)
        body = await self._transport.rpc_request(
            server=server,
            rpc_type=request.MESSAGE_TYPE,
            payload=request.to_payload(),
            timeout_ms=timeout_ms,
        )
        response = MapsFileResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        if response.server != server or response.fileName != file_name:
            raise ValueError("Map file response does not match the request")
        return response

    async def rpc_maps_render(
        self,
        *,
        content_hash: str,
        file: MapBlobRefV1,
        timeout_ms: int,
        layers: dict[str, bool] | None = None,
    ) -> MapsRenderResponseV1:
        """``layers`` are the request's flags (``darkness``, ``buildings``, ...)."""
        request = MapsRenderRequestV1(
            contentHash=content_hash, file=file, **(layers or {})
        )
        body = await self._transport.rpc_request(
            server=RENDERER,
            rpc_type=request.MESSAGE_TYPE,
            payload=request.to_payload(),
            timeout_ms=timeout_ms,
        )
        response = MapsRenderResponseV1.from_payload(
            json.loads(body.get("payload_json", "{}"))
        )
        if response.contentHash != content_hash:
            raise ValueError("Map render response does not match the request")
        return response

    async def renderer_online(self) -> bool:
        """Whether the map renderer announced itself in the last 30 seconds."""
        return bool(await self._transport.redis.exists(RENDERER_PRESENCE_KEY))

    async def write_blob(
        self, data: bytes, content_type: MapBlobRefV1ContentType
    ) -> MapBlobRefV1:
        """Publishes bytes the bot holds (a submitted map file) as a blob for the renderer."""
        if not 0 < len(data) <= MAX_BLOB_BYTES:
            raise ValueError(f"blob size {len(data)} out of range")
        sha = hashlib.sha256(data).hexdigest()
        key = BLOB_PREFIX + sha
        await self._transport.redis.set(key, data, ex=BLOB_TTL_SECONDS)
        return MapBlobRefV1(
            key=key,
            sha256=sha,
            sizeBytes=len(data),
            contentType=content_type,
            expiresAt=int((time.time() + BLOB_TTL_SECONDS) * 1000),
        )

    async def read_blob(self, ref: MapBlobRefV1) -> bytes:
        """The bytes a reference points at, checked against its size and SHA-256."""
        if not 0 < ref.sizeBytes <= MAX_BLOB_BYTES:
            raise BlobUnavailable(f"blob size {ref.sizeBytes} out of range")
        # The bus decodes replies as text; a blob is binary.
        data = await cast(Any, self._transport.redis).execute_command(
            "GET", ref.key, **{NEVER_DECODE: True}
        )
        if data is None:
            raise BlobUnavailable(f"blob {ref.key} is gone")
        if len(data) != ref.sizeBytes or hashlib.sha256(data).hexdigest() != ref.sha256:
            raise BlobUnavailable(f"blob {ref.key} does not match its reference")
        return bytes(data)

    @staticmethod
    def _normalize_map_entry_v1(entry: MapEntryV1) -> dict[str, str]:
        return {
            "name": entry.name,
            "file_name": entry.fileName,
            "author": entry.author,
            "width": str(entry.width) if entry.width is not None else "",
            "height": str(entry.height) if entry.height is not None else "",
            "file_size_bytes": str(entry.fileSizeBytes)
            if entry.fileSizeBytes is not None
            else "",
            "like": str(entry.like) if entry.like is not None else "",
            "dislike": str(entry.dislike) if entry.dislike is not None else "",
            "reputation": str(entry.reputation) if entry.reputation is not None else "",
            "popularity": str(entry.popularity) if entry.popularity is not None else "",
            "interest": str(entry.interest) if entry.interest is not None else "",
            "game_mode": entry.gameMode if entry.gameMode is not None else "",
            "content_hash": entry.contentHash or "",
            **(
                {
                    "uploaded_by_discord_id": entry.discordUpload.uploadedBy.discordId,
                    "uploaded_by_name": entry.discordUpload.uploadedBy.discordUsername
                    or "",
                    "uploaded_at": str(entry.discordUpload.uploadedAt),
                    "uploaded_by_handle": entry.discordUpload.uploadedByHandle or "",
                    "creator_handle": entry.discordUpload.creatorHandle or "",
                    "creator_discord_id": entry.discordUpload.creator.discordId
                    if entry.discordUpload.creator
                    else "",
                    "creator_name": entry.discordUpload.creator.discordUsername or ""
                    if entry.discordUpload.creator
                    else "",
                    "case_number": str(entry.discordUpload.caseNumber or ""),
                    "thread_id": entry.discordUpload.threadId or "",
                    "revision": str(entry.discordUpload.revision or ""),
                    "fixed_by_discord_id": entry.discordUpload.fixedBy.discordId
                    if entry.discordUpload.fixedBy
                    else "",
                    "fixed_by_name": entry.discordUpload.fixedBy.discordUsername or ""
                    if entry.discordUpload.fixedBy
                    else "",
                    "fixed_by_handle": entry.discordUpload.fixedByHandle or "",
                }
                if entry.discordUpload
                else {}
            ),
        }
