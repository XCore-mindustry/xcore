"""Generated canonical maps protocol models."""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, ClassVar

from .shared import (
    DiscordIdentityRefV1,
    MapBlobRefV1,
    MapDiscordUploadV1,
    MapEntryV1,
    MapFileSourceV1,
    MapInspectFindingV1,
    MapInspectInfoV1,
    MapTrashEntryV1,
    MapUploadResultV1,
    MapBlobRefV1ContentType,
    MapInspectFindingV1Severity,
    MapTrashEntryV1Reason,
    MapUploadResultV1Status,
)

def _expect_mapping(value: Any, field_name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise TypeError(f"{field_name} must be an object")
    invalid_keys = [key for key in value.keys() if not isinstance(key, str)]
    if invalid_keys:
        raise TypeError(f"{field_name} keys must be strings")
    return value


def _expect_list(value: Any, field_name: str) -> list[Any]:
    if not isinstance(value, list):
        raise TypeError(f"{field_name} must be a list")
    return value


def _expect_json_object(
    value: Any,
    field_name: str,
    *,
    allowed_types: tuple[str, ...],
    allow_null: bool,
) -> dict[str, Any]:
    mapping = _expect_mapping(value, field_name)
    for key, item in mapping.items():
        if item is None:
            if allow_null:
                continue
            raise TypeError(f"{field_name}.{key} must not be null")
        if isinstance(item, bool):
            allowed = "boolean" in allowed_types
        elif isinstance(item, str):
            allowed = "string" in allowed_types
        elif isinstance(item, int):
            allowed = "integer" in allowed_types or "number" in allowed_types
        elif isinstance(item, float):
            allowed = "number" in allowed_types
        else:
            raise TypeError(f"{field_name}.{key} has unsupported value type")
        if not allowed:
            raise TypeError(
                f"{field_name}.{key} must be one of: {', '.join(allowed_types)}"
            )
    return dict(mapping)


def _expect_exact_keys(
    payload: Mapping[str, Any],
    *,
    required: frozenset[str],
    allowed: frozenset[str],
    model_name: str,
) -> None:
    actual = frozenset(payload.keys())
    missing = sorted(required - actual)
    unexpected = sorted(actual - allowed)
    if missing:
        raise ValueError(f"{model_name} is missing required fields: {', '.join(missing)}")
    if unexpected:
        raise ValueError(f"{model_name} has unexpected fields: {', '.join(unexpected)}")


def _expect_str(value: Any, field_name: str) -> str:
    if not isinstance(value, str):
        raise TypeError(f"{field_name} must be a string")
    return value


def _expect_int(value: Any, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{field_name} must be an integer")
    return value


def _expect_number(value: Any, field_name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError(f"{field_name} must be a number")
    return float(value)


def _expect_bool(value: Any, field_name: str) -> bool:
    if not isinstance(value, bool):
        raise TypeError(f"{field_name} must be a boolean")
    return value


def _expect_enum(value: Any, field_name: str, enum_type: type[StrEnum]) -> StrEnum:
    if not isinstance(value, str):
        raise TypeError(f"{field_name} must be a string")
    try:
        return enum_type(value)
    except ValueError as error:
        allowed = ", ".join(member.value for member in enum_type)
        raise ValueError(f"{field_name} must be one of: {allowed}") from error


def _expect_instance(value: Any, field_name: str, expected_type: type[Any]) -> None:
    if not isinstance(value, expected_type):
        raise TypeError(f"{field_name} must be a {expected_type.__name__}")

@dataclass(frozen=True, slots=True)
class MapsFileRequestV1:
    server: str
    fileName: str

    MESSAGE_TYPE: ClassVar[str] = 'maps.file.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.fileName, 'fileName')
        if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
            raise ValueError("fileName does not match its pattern")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsFileRequestV1":
        mapping = _expect_mapping(payload, "MapsFileRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'fileName')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'fileName')),
            model_name="MapsFileRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            fileName=_expect_str(mapping['fileName'], 'fileName'),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['fileName'] = self.fileName
        return payload

class MapsFileResponseV1Status(StrEnum):
    OK = 'ok'
    NOT_FOUND = 'not_found'
    TOO_LARGE = 'too_large'
    FAILED = 'failed'

@dataclass(frozen=True, slots=True)
class MapsFileResponseV1:
    server: str
    fileName: str
    status: MapsFileResponseV1Status
    contentHash: str | None = None
    file: MapBlobRefV1 | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.file.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.fileName, 'fileName')
        if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
            raise ValueError("fileName does not match its pattern")
        _expect_instance(self.status, 'status', MapsFileResponseV1Status)
        if self.contentHash is not None:
            _expect_str(self.contentHash, 'contentHash')
            if re.search('^[a-f0-9]{64}$', self.contentHash) is None:
                raise ValueError("contentHash does not match its pattern")
        if self.file is not None:
            _expect_instance(self.file, 'file', MapBlobRefV1)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsFileResponseV1":
        mapping = _expect_mapping(payload, "MapsFileResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'fileName', 'status')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'fileName', 'status', 'contentHash', 'file')),
            model_name="MapsFileResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            fileName=_expect_str(mapping['fileName'], 'fileName'),
            status=_expect_enum(mapping['status'], 'status', MapsFileResponseV1Status),
            contentHash=(_expect_str(mapping['contentHash'], 'contentHash') if 'contentHash' in mapping else None),
            file=(MapBlobRefV1.from_payload(_expect_mapping(mapping['file'], 'file')) if 'file' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['fileName'] = self.fileName
        payload['status'] = str(self.status)
        if self.contentHash is not None:
            payload['contentHash'] = self.contentHash
        if self.file is not None:
            payload['file'] = self.file.to_payload()
        return payload

@dataclass(frozen=True, slots=True)
class MapsInspectRequestV1:
    server: str
    file: MapFileSourceV1
    profile: str | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.inspect.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_instance(self.file, 'file', MapFileSourceV1)
        if self.profile is not None:
            _expect_str(self.profile, 'profile')

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsInspectRequestV1":
        mapping = _expect_mapping(payload, "MapsInspectRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'file')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'file', 'profile')),
            model_name="MapsInspectRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            file=MapFileSourceV1.from_payload(_expect_mapping(mapping['file'], 'file')),
            profile=(_expect_str(mapping['profile'], 'profile') if 'profile' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['file'] = self.file.to_payload()
        if self.profile is not None:
            payload['profile'] = self.profile
        return payload

class MapsInspectResponseV1Status(StrEnum):
    OK = 'ok'
    DOWNLOAD_FAILED = 'download_failed'
    INVALID_MAP = 'invalid_map'

@dataclass(frozen=True, slots=True)
class MapsInspectResponseV1:
    server: str
    status: MapsInspectResponseV1Status
    findings: tuple[MapInspectFindingV1, ...]
    detail: str | None = None
    info: MapInspectInfoV1 | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.inspect.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_instance(self.status, 'status', MapsInspectResponseV1Status)
        if self.detail is not None:
            _expect_str(self.detail, 'detail')
        if self.info is not None:
            _expect_instance(self.info, 'info', MapInspectInfoV1)
        if not isinstance(self.findings, tuple):
            raise TypeError("findings must be a tuple")
        for item in self.findings:
            _expect_instance(item, 'findings[]', MapInspectFindingV1)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsInspectResponseV1":
        mapping = _expect_mapping(payload, "MapsInspectResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'status', 'findings')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'status', 'detail', 'info', 'findings')),
            model_name="MapsInspectResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            status=_expect_enum(mapping['status'], 'status', MapsInspectResponseV1Status),
            detail=(_expect_str(mapping['detail'], 'detail') if 'detail' in mapping else None),
            info=(MapInspectInfoV1.from_payload(_expect_mapping(mapping['info'], 'info')) if 'info' in mapping else None),
            findings=tuple(MapInspectFindingV1.from_payload(_expect_mapping(item, 'findings[]')) for item in _expect_list(mapping['findings'], 'findings')),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['status'] = str(self.status)
        if self.detail is not None:
            payload['detail'] = self.detail
        if self.info is not None:
            payload['info'] = self.info.to_payload()
        payload['findings'] = [item.to_payload() for item in self.findings]
        return payload

@dataclass(frozen=True, slots=True)
class MapsListRequestV1:
    server: str

    MESSAGE_TYPE: ClassVar[str] = 'maps.list.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsListRequestV1":
        mapping = _expect_mapping(payload, "MapsListRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server')),
            allowed=frozenset(('messageType', 'messageVersion', 'server')),
            model_name="MapsListRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        return payload

@dataclass(frozen=True, slots=True)
class MapsListResponseV1:
    server: str
    maps: tuple[MapEntryV1, ...]

    MESSAGE_TYPE: ClassVar[str] = 'maps.list.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        if not isinstance(self.maps, tuple):
            raise TypeError("maps must be a tuple")
        for item in self.maps:
            _expect_instance(item, 'maps[]', MapEntryV1)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsListResponseV1":
        mapping = _expect_mapping(payload, "MapsListResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'maps')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'maps')),
            model_name="MapsListResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            maps=tuple(MapEntryV1.from_payload(_expect_mapping(item, 'maps[]')) for item in _expect_list(mapping['maps'], 'maps')),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['maps'] = [item.to_payload() for item in self.maps]
        return payload

@dataclass(frozen=True, slots=True)
class MapsLoadCommandV1:
    server: str
    files: tuple[MapFileSourceV1, ...]

    MESSAGE_TYPE: ClassVar[str] = 'maps.load.command'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        if not isinstance(self.files, tuple):
            raise TypeError("files must be a tuple")
        for item in self.files:
            _expect_instance(item, 'files[]', MapFileSourceV1)
        if len(self.files) < 1:
            raise ValueError("files has too few items")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsLoadCommandV1":
        mapping = _expect_mapping(payload, "MapsLoadCommandV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'files')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'files')),
            model_name="MapsLoadCommandV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            files=tuple(MapFileSourceV1.from_payload(_expect_mapping(item, 'files[]')) for item in _expect_list(mapping['files'], 'files')),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['files'] = [item.to_payload() for item in self.files]
        return payload

@dataclass(frozen=True, slots=True)
class MapsPreviewRequestV1:
    server: str
    fileName: str
    maxSide: int | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.preview.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.fileName, 'fileName')
        if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
            raise ValueError("fileName does not match its pattern")
        if self.maxSide is not None:
            _expect_int(self.maxSide, 'maxSide')
            if self.maxSide < 64:
                raise ValueError("maxSide must be >= 64")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsPreviewRequestV1":
        mapping = _expect_mapping(payload, "MapsPreviewRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'fileName')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'fileName', 'maxSide')),
            model_name="MapsPreviewRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            fileName=_expect_str(mapping['fileName'], 'fileName'),
            maxSide=(_expect_int(mapping['maxSide'], 'maxSide') if 'maxSide' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['fileName'] = self.fileName
        if self.maxSide is not None:
            payload['maxSide'] = self.maxSide
        return payload

class MapsPreviewResponseV1Status(StrEnum):
    OK = 'ok'
    NOT_FOUND = 'not_found'
    FAILED = 'failed'

@dataclass(frozen=True, slots=True)
class MapsPreviewResponseV1:
    server: str
    fileName: str
    status: MapsPreviewResponseV1Status
    contentHash: str | None = None
    image: MapBlobRefV1 | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.preview.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.fileName, 'fileName')
        if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
            raise ValueError("fileName does not match its pattern")
        _expect_instance(self.status, 'status', MapsPreviewResponseV1Status)
        if self.contentHash is not None:
            _expect_str(self.contentHash, 'contentHash')
            if re.search('^[a-f0-9]{64}$', self.contentHash) is None:
                raise ValueError("contentHash does not match its pattern")
        if self.image is not None:
            _expect_instance(self.image, 'image', MapBlobRefV1)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsPreviewResponseV1":
        mapping = _expect_mapping(payload, "MapsPreviewResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'fileName', 'status')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'fileName', 'status', 'contentHash', 'image')),
            model_name="MapsPreviewResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            fileName=_expect_str(mapping['fileName'], 'fileName'),
            status=_expect_enum(mapping['status'], 'status', MapsPreviewResponseV1Status),
            contentHash=(_expect_str(mapping['contentHash'], 'contentHash') if 'contentHash' in mapping else None),
            image=(MapBlobRefV1.from_payload(_expect_mapping(mapping['image'], 'image')) if 'image' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['fileName'] = self.fileName
        payload['status'] = str(self.status)
        if self.contentHash is not None:
            payload['contentHash'] = self.contentHash
        if self.image is not None:
            payload['image'] = self.image.to_payload()
        return payload

@dataclass(frozen=True, slots=True)
class MapsRemoveRequestV1:
    server: str
    fileName: str
    expectedContentHash: str | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.remove.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.fileName, 'fileName')
        if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
            raise ValueError("fileName does not match its pattern")
        if self.expectedContentHash is not None:
            _expect_str(self.expectedContentHash, 'expectedContentHash')
            if re.search('^[a-f0-9]{64}$', self.expectedContentHash) is None:
                raise ValueError("expectedContentHash does not match its pattern")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsRemoveRequestV1":
        mapping = _expect_mapping(payload, "MapsRemoveRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'fileName')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'fileName', 'expectedContentHash')),
            model_name="MapsRemoveRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            fileName=_expect_str(mapping['fileName'], 'fileName'),
            expectedContentHash=(_expect_str(mapping['expectedContentHash'], 'expectedContentHash') if 'expectedContentHash' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['fileName'] = self.fileName
        if self.expectedContentHash is not None:
            payload['expectedContentHash'] = self.expectedContentHash
        return payload

class MapsRemoveResponseV1Status(StrEnum):
    REMOVED = 'removed'
    NOT_FOUND = 'not_found'
    CONFLICT = 'conflict'
    FAILED = 'failed'

@dataclass(frozen=True, slots=True)
class MapsRemoveResponseV1:
    server: str
    result: str
    status: MapsRemoveResponseV1Status | None = None
    contentHash: str | None = None
    trashId: str | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.remove.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.result, 'result')
        if len(self.result) < 1:
            raise ValueError("result is too short")
        if self.status is not None:
            _expect_instance(self.status, 'status', MapsRemoveResponseV1Status)
        if self.contentHash is not None:
            _expect_str(self.contentHash, 'contentHash')
            if re.search('^[a-f0-9]{64}$', self.contentHash) is None:
                raise ValueError("contentHash does not match its pattern")
        if self.trashId is not None:
            _expect_str(self.trashId, 'trashId')
            if re.search('^[A-Za-z0-9._-]{1,160}$', self.trashId) is None:
                raise ValueError("trashId does not match its pattern")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsRemoveResponseV1":
        mapping = _expect_mapping(payload, "MapsRemoveResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'result')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'result', 'status', 'contentHash', 'trashId')),
            model_name="MapsRemoveResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            result=_expect_str(mapping['result'], 'result'),
            status=(_expect_enum(mapping['status'], 'status', MapsRemoveResponseV1Status) if 'status' in mapping else None),
            contentHash=(_expect_str(mapping['contentHash'], 'contentHash') if 'contentHash' in mapping else None),
            trashId=(_expect_str(mapping['trashId'], 'trashId') if 'trashId' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['result'] = self.result
        if self.status is not None:
            payload['status'] = str(self.status)
        if self.contentHash is not None:
            payload['contentHash'] = self.contentHash
        if self.trashId is not None:
            payload['trashId'] = self.trashId
        return payload

@dataclass(frozen=True, slots=True)
class MapsRenderRequestV1:
    contentHash: str
    file: MapBlobRefV1
    maxSide: int | None = None
    maxBytes: int | None = None
    darkness: bool | None = None
    buildings: bool | None = None
    ores: bool | None = None
    grid: bool | None = None
    spawns: bool | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.render.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.contentHash, 'contentHash')
        if re.search('^[a-f0-9]{64}$', self.contentHash) is None:
            raise ValueError("contentHash does not match its pattern")
        _expect_instance(self.file, 'file', MapBlobRefV1)
        if self.maxSide is not None:
            _expect_int(self.maxSide, 'maxSide')
            if self.maxSide < 256:
                raise ValueError("maxSide must be >= 256")
        if self.maxBytes is not None:
            _expect_int(self.maxBytes, 'maxBytes')
            if self.maxBytes < 65536:
                raise ValueError("maxBytes must be >= 65536")
        if self.darkness is not None:
            _expect_bool(self.darkness, 'darkness')
        if self.buildings is not None:
            _expect_bool(self.buildings, 'buildings')
        if self.ores is not None:
            _expect_bool(self.ores, 'ores')
        if self.grid is not None:
            _expect_bool(self.grid, 'grid')
        if self.spawns is not None:
            _expect_bool(self.spawns, 'spawns')

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsRenderRequestV1":
        mapping = _expect_mapping(payload, "MapsRenderRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'contentHash', 'file')),
            allowed=frozenset(('messageType', 'messageVersion', 'contentHash', 'file', 'maxSide', 'maxBytes', 'darkness', 'buildings', 'ores', 'grid', 'spawns')),
            model_name="MapsRenderRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            contentHash=_expect_str(mapping['contentHash'], 'contentHash'),
            file=MapBlobRefV1.from_payload(_expect_mapping(mapping['file'], 'file')),
            maxSide=(_expect_int(mapping['maxSide'], 'maxSide') if 'maxSide' in mapping else None),
            maxBytes=(_expect_int(mapping['maxBytes'], 'maxBytes') if 'maxBytes' in mapping else None),
            darkness=(_expect_bool(mapping['darkness'], 'darkness') if 'darkness' in mapping else None),
            buildings=(_expect_bool(mapping['buildings'], 'buildings') if 'buildings' in mapping else None),
            ores=(_expect_bool(mapping['ores'], 'ores') if 'ores' in mapping else None),
            grid=(_expect_bool(mapping['grid'], 'grid') if 'grid' in mapping else None),
            spawns=(_expect_bool(mapping['spawns'], 'spawns') if 'spawns' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['contentHash'] = self.contentHash
        payload['file'] = self.file.to_payload()
        if self.maxSide is not None:
            payload['maxSide'] = self.maxSide
        if self.maxBytes is not None:
            payload['maxBytes'] = self.maxBytes
        if self.darkness is not None:
            payload['darkness'] = self.darkness
        if self.buildings is not None:
            payload['buildings'] = self.buildings
        if self.ores is not None:
            payload['ores'] = self.ores
        if self.grid is not None:
            payload['grid'] = self.grid
        if self.spawns is not None:
            payload['spawns'] = self.spawns
        return payload

class MapsRenderResponseV1Status(StrEnum):
    OK = 'ok'
    BUSY = 'busy'
    TOO_LARGE = 'too_large'
    INVALID_MAP = 'invalid_map'
    DOWNLOAD_FAILED = 'download_failed'
    FAILED = 'failed'

@dataclass(frozen=True, slots=True)
class MapsRenderResponseV1:
    contentHash: str
    status: MapsRenderResponseV1Status
    image: MapBlobRefV1 | None = None
    scale: int | None = None
    renderMs: int | None = None
    reason: str | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.render.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.contentHash, 'contentHash')
        if re.search('^[a-f0-9]{64}$', self.contentHash) is None:
            raise ValueError("contentHash does not match its pattern")
        _expect_instance(self.status, 'status', MapsRenderResponseV1Status)
        if self.image is not None:
            _expect_instance(self.image, 'image', MapBlobRefV1)
        if self.scale is not None:
            _expect_int(self.scale, 'scale')
            if self.scale < 1:
                raise ValueError("scale must be >= 1")
        if self.renderMs is not None:
            _expect_int(self.renderMs, 'renderMs')
            if self.renderMs < 0:
                raise ValueError("renderMs must be >= 0")
        if self.reason is not None:
            _expect_str(self.reason, 'reason')

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsRenderResponseV1":
        mapping = _expect_mapping(payload, "MapsRenderResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'contentHash', 'status')),
            allowed=frozenset(('messageType', 'messageVersion', 'contentHash', 'status', 'image', 'scale', 'renderMs', 'reason')),
            model_name="MapsRenderResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            contentHash=_expect_str(mapping['contentHash'], 'contentHash'),
            status=_expect_enum(mapping['status'], 'status', MapsRenderResponseV1Status),
            image=(MapBlobRefV1.from_payload(_expect_mapping(mapping['image'], 'image')) if 'image' in mapping else None),
            scale=(_expect_int(mapping['scale'], 'scale') if 'scale' in mapping else None),
            renderMs=(_expect_int(mapping['renderMs'], 'renderMs') if 'renderMs' in mapping else None),
            reason=(_expect_str(mapping['reason'], 'reason') if 'reason' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['contentHash'] = self.contentHash
        payload['status'] = str(self.status)
        if self.image is not None:
            payload['image'] = self.image.to_payload()
        if self.scale is not None:
            payload['scale'] = self.scale
        if self.renderMs is not None:
            payload['renderMs'] = self.renderMs
        if self.reason is not None:
            payload['reason'] = self.reason
        return payload

@dataclass(frozen=True, slots=True)
class MapsRestoreRequestV1:
    server: str
    trashId: str
    fileName: str | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.restore.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.trashId, 'trashId')
        if re.search('^[A-Za-z0-9._-]{1,160}$', self.trashId) is None:
            raise ValueError("trashId does not match its pattern")
        if self.fileName is not None:
            _expect_str(self.fileName, 'fileName')
            if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
                raise ValueError("fileName does not match its pattern")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsRestoreRequestV1":
        mapping = _expect_mapping(payload, "MapsRestoreRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'trashId')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'trashId', 'fileName')),
            model_name="MapsRestoreRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            trashId=_expect_str(mapping['trashId'], 'trashId'),
            fileName=(_expect_str(mapping['fileName'], 'fileName') if 'fileName' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['trashId'] = self.trashId
        if self.fileName is not None:
            payload['fileName'] = self.fileName
        return payload

class MapsRestoreResponseV1Status(StrEnum):
    RESTORED = 'restored'
    NOT_FOUND = 'not_found'
    CONFLICT = 'conflict'
    FAILED = 'failed'

@dataclass(frozen=True, slots=True)
class MapsRestoreResponseV1:
    server: str
    trashId: str
    status: MapsRestoreResponseV1Status
    fileName: str | None = None
    contentHash: str | None = None
    detail: str | None = None

    MESSAGE_TYPE: ClassVar[str] = 'maps.restore.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        _expect_str(self.trashId, 'trashId')
        if re.search('^[A-Za-z0-9._-]{1,160}$', self.trashId) is None:
            raise ValueError("trashId does not match its pattern")
        _expect_instance(self.status, 'status', MapsRestoreResponseV1Status)
        if self.fileName is not None:
            _expect_str(self.fileName, 'fileName')
            if re.search('^[^/\\\\]+\\.[mM][sS][aA][vV]$', self.fileName) is None:
                raise ValueError("fileName does not match its pattern")
        if self.contentHash is not None:
            _expect_str(self.contentHash, 'contentHash')
            if re.search('^[a-f0-9]{64}$', self.contentHash) is None:
                raise ValueError("contentHash does not match its pattern")
        if self.detail is not None:
            _expect_str(self.detail, 'detail')
            if len(self.detail) < 1:
                raise ValueError("detail is too short")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsRestoreResponseV1":
        mapping = _expect_mapping(payload, "MapsRestoreResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'trashId', 'status')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'trashId', 'status', 'fileName', 'contentHash', 'detail')),
            model_name="MapsRestoreResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            trashId=_expect_str(mapping['trashId'], 'trashId'),
            status=_expect_enum(mapping['status'], 'status', MapsRestoreResponseV1Status),
            fileName=(_expect_str(mapping['fileName'], 'fileName') if 'fileName' in mapping else None),
            contentHash=(_expect_str(mapping['contentHash'], 'contentHash') if 'contentHash' in mapping else None),
            detail=(_expect_str(mapping['detail'], 'detail') if 'detail' in mapping else None),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['trashId'] = self.trashId
        payload['status'] = str(self.status)
        if self.fileName is not None:
            payload['fileName'] = self.fileName
        if self.contentHash is not None:
            payload['contentHash'] = self.contentHash
        if self.detail is not None:
            payload['detail'] = self.detail
        return payload

@dataclass(frozen=True, slots=True)
class MapsTrashListRequestV1:
    server: str

    MESSAGE_TYPE: ClassVar[str] = 'maps.trash.list.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsTrashListRequestV1":
        mapping = _expect_mapping(payload, "MapsTrashListRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server')),
            allowed=frozenset(('messageType', 'messageVersion', 'server')),
            model_name="MapsTrashListRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        return payload

@dataclass(frozen=True, slots=True)
class MapsTrashListResponseV1:
    server: str
    entries: tuple[MapTrashEntryV1, ...]

    MESSAGE_TYPE: ClassVar[str] = 'maps.trash.list.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        if not isinstance(self.entries, tuple):
            raise TypeError("entries must be a tuple")
        for item in self.entries:
            _expect_instance(item, 'entries[]', MapTrashEntryV1)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsTrashListResponseV1":
        mapping = _expect_mapping(payload, "MapsTrashListResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'entries')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'entries')),
            model_name="MapsTrashListResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            entries=tuple(MapTrashEntryV1.from_payload(_expect_mapping(item, 'entries[]')) for item in _expect_list(mapping['entries'], 'entries')),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['entries'] = [item.to_payload() for item in self.entries]
        return payload

@dataclass(frozen=True, slots=True)
class MapsUploadRequestV1:
    server: str
    files: tuple[MapFileSourceV1, ...]

    MESSAGE_TYPE: ClassVar[str] = 'maps.upload.request'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        if not isinstance(self.files, tuple):
            raise TypeError("files must be a tuple")
        for item in self.files:
            _expect_instance(item, 'files[]', MapFileSourceV1)
        if len(self.files) < 1:
            raise ValueError("files has too few items")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsUploadRequestV1":
        mapping = _expect_mapping(payload, "MapsUploadRequestV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'files')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'files')),
            model_name="MapsUploadRequestV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            files=tuple(MapFileSourceV1.from_payload(_expect_mapping(item, 'files[]')) for item in _expect_list(mapping['files'], 'files')),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['files'] = [item.to_payload() for item in self.files]
        return payload

@dataclass(frozen=True, slots=True)
class MapsUploadResponseV1:
    server: str
    results: tuple[MapUploadResultV1, ...]

    MESSAGE_TYPE: ClassVar[str] = 'maps.upload.response'
    MESSAGE_VERSION: ClassVar[int] = 1
    def __post_init__(self) -> None:
        _expect_str(self.server, 'server')
        if len(self.server) < 1:
            raise ValueError("server is too short")
        if not isinstance(self.results, tuple):
            raise TypeError("results must be a tuple")
        for item in self.results:
            _expect_instance(item, 'results[]', MapUploadResultV1)
        if len(self.results) < 1:
            raise ValueError("results has too few items")

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> "MapsUploadResponseV1":
        mapping = _expect_mapping(payload, "MapsUploadResponseV1")
        _expect_exact_keys(
            mapping,
            required=frozenset(('messageType', 'messageVersion', 'server', 'results')),
            allowed=frozenset(('messageType', 'messageVersion', 'server', 'results')),
            model_name="MapsUploadResponseV1",
        )
        if mapping['messageType'] != cls.MESSAGE_TYPE:
            raise ValueError('messageType' + " must equal " + repr(cls.MESSAGE_TYPE))
        if mapping['messageVersion'] != cls.MESSAGE_VERSION:
            raise ValueError('messageVersion' + " must equal " + repr(cls.MESSAGE_VERSION))
        return cls(
            server=_expect_str(mapping['server'], 'server'),
            results=tuple(MapUploadResultV1.from_payload(_expect_mapping(item, 'results[]')) for item in _expect_list(mapping['results'], 'results')),
        )

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            'messageType': self.MESSAGE_TYPE,
            'messageVersion': self.MESSAGE_VERSION,
        }
        payload['server'] = self.server
        payload['results'] = [item.to_payload() for item in self.results]
        return payload

__all__ = [
    "MapsFileRequestV1",
    "MapsFileResponseV1",
    "MapsInspectRequestV1",
    "MapsInspectResponseV1",
    "MapsListRequestV1",
    "MapsListResponseV1",
    "MapsLoadCommandV1",
    "MapsPreviewRequestV1",
    "MapsPreviewResponseV1",
    "MapsRemoveRequestV1",
    "MapsRemoveResponseV1",
    "MapsRenderRequestV1",
    "MapsRenderResponseV1",
    "MapsRestoreRequestV1",
    "MapsRestoreResponseV1",
    "MapsTrashListRequestV1",
    "MapsTrashListResponseV1",
    "MapsUploadRequestV1",
    "MapsUploadResponseV1",
    "MapsFileResponseV1Status",
    "MapsInspectResponseV1Status",
    "MapsPreviewResponseV1Status",
    "MapsRemoveResponseV1Status",
    "MapsRenderResponseV1Status",
    "MapsRestoreResponseV1Status",
]
