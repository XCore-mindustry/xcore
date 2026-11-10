"""Migration checks against explicitly supplied, disposable test backends."""

from __future__ import annotations

import json
import os
from pathlib import Path
from uuid import uuid4

import pytest
from bot_fakes import make_settings
from pymongo import AsyncMongoClient

from xcore_discord_bot.bus.transport import RedisTransport
from xcore_discord_bot.mongo_store import MongoStore
from xcore_discord_bot.store_mappers import player_record_from_doc


@pytest.mark.skipif(not os.getenv("XCORE_TEST_MONGO_URL"), reason="No test MongoDB")
async def test_player_contract_roundtrips_through_mongo() -> None:
    uri = os.environ["XCORE_TEST_MONGO_URL"]
    database = f"xcore_migration_test_{uuid4().hex}"
    document = json.loads(
        (Path(__file__).parent / "fixtures/player_document.json").read_text()
    )
    client = AsyncMongoClient(uri, serverSelectionTimeoutMS=5000)
    store = MongoStore(make_settings(mongo_uri=uri, mongo_db_name=database))
    try:
        await client[database]["players"].insert_one(dict(document))
        await store.connect()
        assert await store.find_player_by_pid(
            document["pid"]
        ) == player_record_from_doc(document)
    finally:
        await store.close()
        await client.drop_database(database)
        await client.close()


@pytest.mark.skipif(not os.getenv("XCORE_TEST_REDIS_URL"), reason="No test Redis")
async def test_idempotency_survives_a_real_redis_reconnection() -> None:
    key = f"migration-test:{uuid4().hex}"
    transport = RedisTransport(
        make_settings(redis_url=os.environ["XCORE_TEST_REDIS_URL"])
    )
    await transport.connect()
    try:
        assert await transport.claim_idempotency(key)
        await transport.reconnect()
        assert not await transport.claim_idempotency(key)
    finally:
        await transport.redis.delete(f"xcore:idmp:{key}")
        await transport.close()


@pytest.mark.skipif(not os.getenv("XCORE_TEST_MONGO_URL"), reason="No test MongoDB")
async def test_case_thread_index_moves_to_one_open_case_per_thread() -> None:
    """A map post holds one case per revision; the old index allowed one case at all."""
    from pymongo.errors import DuplicateKeyError

    from xcore_discord_bot.cases.store import THREAD_INDEX, CaseStore

    uri = os.environ["XCORE_TEST_MONGO_URL"]
    database = f"xcore_migration_test_{uuid4().hex}"
    client = AsyncMongoClient(uri, serverSelectionTimeoutMS=5000)
    store = MongoStore(make_settings(mongo_uri=uri, mongo_db_name=database))
    try:
        cases = client[database]["cases"]
        await cases.create_index("discord.thread_id", unique=True, sparse=True)
        await store.connect()
        await CaseStore(store).ensure_indexes()
        await CaseStore(store).ensure_indexes()  # a second start changes nothing
        info = await cases.index_information()
        assert "discord.thread_id_1" not in info and THREAD_INDEX in info
        post = {"thread_id": "1"}
        for case_id, active in ((1, False), (2, True)):
            await cases.insert_one(
                {
                    "_id": case_id,
                    "active": active,
                    "discord": post,
                    "origin": {"submission_id": str(case_id)},
                }
            )
        with pytest.raises(DuplicateKeyError, match=THREAD_INDEX):
            await cases.insert_one(
                {
                    "_id": 3,
                    "active": True,
                    "discord": post,
                    "origin": {"submission_id": "3"},
                }
            )
    finally:
        await store.close()
        await client.drop_database(database)
        await client.close()
