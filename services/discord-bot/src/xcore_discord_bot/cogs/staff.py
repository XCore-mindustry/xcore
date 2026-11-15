from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

import discord
from discord import Interaction, app_commands
from discord.ext import commands

from .. import staff_activity
from ..command_helpers import send_paginated
from ..permissions_config import load_discord_permissions
from .checks import head_admin_check

if TYPE_CHECKING:
    from ..bot import XCoreDiscordBot


class StaffCog(commands.Cog):
    staff_group = app_commands.Group(name="staff", description="Staff tools")

    def __init__(self, bot: XCoreDiscordBot) -> None:
        self.bot = bot

    def _roster(self) -> frozenset[str]:
        """Discord ids holding any role the game servers bind to a staff role."""
        settings = self.bot.settings
        role_ids = load_discord_permissions(
            settings.permissions_config_path, settings.discord_guild_id
        )
        guild = self.bot.get_guild(settings.discord_guild_id)
        if guild is None:
            return frozenset()
        return frozenset(
            str(member.id)
            for role_id in role_ids
            if (role := guild.get_role(int(role_id))) is not None
            for member in role.members
            if not member.bot
        )

    @staff_group.command(
        name="activity", description="What each staff member did in the last days"
    )
    @app_commands.describe(
        days="Days to look back, today included (1-90)",
        member="Show one staff member day by day",
    )
    @head_admin_check()
    async def cmd_activity(
        self,
        interaction: Interaction,
        days: app_commands.Range[int, 1, 90] = 14,
        member: discord.User | None = None,
    ) -> None:
        now = datetime.now(UTC)
        report = await staff_activity.load(
            self.bot.store.database, self._roster(), days, now
        )
        today = now.date()
        if member is None:
            title = f"Staff activity, last {days} days"
            lines = staff_activity.summary_lines(report, today)
        else:
            title = f"{member.display_name}, last {days} days"
            lines = staff_activity.member_lines(report, str(member.id), today)
        pages = staff_activity.pages(lines)

        async def fetch_page(page: int) -> tuple[discord.Embed, bool]:
            embed = discord.Embed(
                title=title, description=pages[page], color=discord.Color.blurple()
            )
            embed.set_footer(
                text=f"{staff_activity.LEGEND} Page {page + 1}/{len(pages)}"
            )
            return embed, page + 1 < len(pages)

        await send_paginated(
            interaction,
            fetch_page,
            ephemeral=True,
            allowed_mentions=discord.AllowedMentions.none(),
        )
