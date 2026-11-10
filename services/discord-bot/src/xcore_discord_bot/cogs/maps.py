from __future__ import annotations

from typing import TYPE_CHECKING, Any, cast

import discord
from discord import Interaction, app_commands
from discord.ext import commands

from .. import handlers_maps, map_admin
from ..cases.flows.maps import intake
from ..cases.modals import error_text
from ..cases.model import AccessDenied, ActionFailed, CaseKind
from ..i18n import t
from ..map_admin import list_maps_or_none
from ..map_card import find_map
from ..map_list import map_text
from ..permissions import has_any_role, map_reviewer_role_ids
from .autocomplete import autocomplete_map_file
from .checks import map_reviewer_check

if TYPE_CHECKING:
    from ..bot import XCoreDiscordBot


async def _autocomplete_server_for_command(
    interaction: Interaction,
    current: str,
) -> list[app_commands.Choice[str]]:
    bot = cast("XCoreDiscordBot", interaction.client)
    current_norm = current.strip().lower()
    choices: list[app_commands.Choice[str]] = []
    for server in bot.container.servers.names():
        if current_norm and current_norm not in server.lower():
            continue
        choices.append(app_commands.Choice(name=server, value=server))
        if len(choices) >= 25:
            break
    return choices


class MapsCog(commands.Cog):
    """``/maps …`` browses and manages a server's maps; ``/map`` opens one of them."""

    maps_group = app_commands.Group(
        name="maps",
        description="Browse and manage server maps",
    )

    def __init__(self, bot: XCoreDiscordBot) -> None:
        self.bot = bot

    @app_commands.command(name="map", description="Show one map in detail")
    @app_commands.describe(server="Server name", file_name="The map")
    @app_commands.rename(file_name="map")
    @app_commands.autocomplete(server=_autocomplete_server_for_command)
    @app_commands.autocomplete(file_name=autocomplete_map_file)
    async def cmd_map(
        self, interaction: Interaction, server: str, file_name: str
    ) -> None:
        await handlers_maps.cmd_map_details(self.bot, interaction, server, file_name)

    @maps_group.command(
        name="review", description="Start map review in this forum post"
    )
    @app_commands.describe(
        server="Server where this post's map is already installed (optional)",
        replaces="The installed map this post updates (optional, with server)",
    )
    @app_commands.autocomplete(server=_autocomplete_server_for_command)
    @app_commands.autocomplete(replaces=autocomplete_map_file)
    async def cmd_review_map(
        self,
        interaction: Interaction,
        server: str | None = None,
        replaces: str | None = None,
    ) -> None:
        await interaction.response.defer(ephemeral=True, thinking=True)
        service = self.bot.container.cases
        locale = await service.store.remember_interaction(interaction)
        try:
            service.registry.require(CaseKind.MAP)
            if not has_any_role(
                interaction.user, map_reviewer_role_ids(service.settings)
            ):
                raise AccessDenied()
            # A post in the maps forum; anything else is refused just below.
            thread: Any = interaction.channel
            if (
                thread is None
                or getattr(thread, "parent_id", None)
                != service.settings.discord_maps_forum_id
            ):
                raise ActionFailed(t(locale, "maps-review-in-post"))
            if str(interaction.user.id) == str(thread.owner_id):
                raise AccessDenied()
            installed = await self._binding(locale, server, replaces)
            case = await intake(service, thread, manual=True, installed=installed)
            if case:
                text = t(locale, "cases-updated", number=case.number)
            elif await service.store.map_cases(thread.id):
                text = t(locale, "maps-review-nothing-new")
            else:
                text = t(locale, "maps-review-no-file")
            await interaction.followup.send(text, ephemeral=True)
        except ValueError as error:
            await interaction.followup.send(error_text(locale, error), ephemeral=True)

    async def _binding(
        self, locale: str, server: str | None, replaces: str | None
    ) -> dict[str, str | None] | None:
        """The installed map a post updates, as the reviewer named it, checked on the server."""
        if not server and not replaces:
            return None
        if not server or not replaces:
            raise ActionFailed(t(locale, "maps-review-binding-both"))
        maps = await list_maps_or_none(self.bot, server)
        if maps is None:
            raise ActionFailed(t(locale, "maps-list-timeout"))
        item = find_map(maps, replaces)
        if item is None:
            raise ActionFailed(
                t(
                    locale,
                    "maps-card-not-found",
                    server=map_text(server, inline_code=True),
                    file=map_text(replaces, 100, inline_code=True),
                )
            )
        return {
            "server": server,
            "file_name": item["file_name"],
            "sha256": item.get("content_hash") or None,
        }

    @maps_group.command(name="list", description="List maps on a server")
    @app_commands.describe(
        server="Server name", creator="Only maps by this declared creator"
    )
    @app_commands.autocomplete(server=_autocomplete_server_for_command)
    async def cmd_maps(
        self, interaction: Interaction, server: str, creator: discord.User | None = None
    ) -> None:
        await handlers_maps.cmd_maps(self.bot, interaction, server, creator=creator)

    @maps_group.command(
        name="remove",
        description="Remove a map from a server (map reviewer)",
    )
    @app_commands.describe(
        server="Server name", file_name="Map file name (.msav) to remove"
    )
    @app_commands.autocomplete(server=_autocomplete_server_for_command)
    @app_commands.autocomplete(file_name=autocomplete_map_file)
    @map_reviewer_check()
    async def cmd_remove_map(
        self,
        interaction: Interaction,
        server: str,
        file_name: str,
    ) -> None:
        await map_admin.cmd_remove_map(self.bot, interaction, server, file_name)

    @maps_group.command(
        name="duplicates",
        description="Find maps installed twice on a server (map reviewer)",
    )
    @app_commands.describe(server="Server name")
    @app_commands.autocomplete(server=_autocomplete_server_for_command)
    @map_reviewer_check()
    async def cmd_duplicates(self, interaction: Interaction, server: str) -> None:
        await map_admin.cmd_duplicates(self.bot, interaction, server)

    @maps_group.command(
        name="rollback",
        description="Put an earlier accepted revision of this post's map back (map reviewer)",
    )
    @map_reviewer_check()
    async def cmd_rollback(self, interaction: Interaction) -> None:
        await map_admin.cmd_rollback(self.bot, interaction)

    @maps_group.command(
        name="upload",
        description="Upload .msav map files to a server (up to 3 per command)",
    )
    @app_commands.describe(
        server="Server name",
        file1="Map file (.msav)",
        file2="Second .msav file (optional)",
        file3="Third .msav file (optional)",
        creator="Default creator for all files (optional)",
        creator1="Creator of file1 (overrides the default)",
        creator2="Creator of file2 (overrides the default)",
        creator3="Creator of file3 (overrides the default)",
        replace="Installed map this file replaces (one file only)",
    )
    @app_commands.autocomplete(server=_autocomplete_server_for_command)
    @app_commands.autocomplete(replace=autocomplete_map_file)
    @map_reviewer_check()
    async def cmd_upload_map(
        self,
        interaction: Interaction,
        server: str,
        file1: discord.Attachment,
        file2: discord.Attachment | None = None,
        file3: discord.Attachment | None = None,
        creator: discord.User | None = None,
        creator1: discord.User | None = None,
        creator2: discord.User | None = None,
        creator3: discord.User | None = None,
        replace: str | None = None,
    ) -> None:
        await map_admin.cmd_upload_map(
            self.bot,
            interaction,
            server,
            [file1, file2, file3],
            creator=creator,
            creators=[creator1, creator2, creator3],
            replace=replace,
        )
