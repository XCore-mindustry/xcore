from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Literal

import discord
from discord import Interaction

from .i18n import DEFAULT, t
from .presentation import build_servers_embed, sort_live_servers
from .registry import LiveServerRegistry
from .ui_helpers import disable_view_buttons, safe_edit_view_message

MapSortMode = Literal["reputation", "popularity", "name"]


class PaginatorView(discord.ui.View):
    def __init__(
        self,
        *,
        page: int,
        has_prev: bool,
        has_next: bool,
        fetch_page: Callable[[int], Awaitable[tuple[discord.Embed, bool]]],
    ) -> None:
        super().__init__(timeout=120)
        self._page = page
        self._fetch_page = fetch_page
        self.bot_message: discord.Message | None = None
        self._prev_btn.disabled = not has_prev
        self._next_btn.disabled = not has_next

    @discord.ui.button(label="◀ Prev", style=discord.ButtonStyle.secondary)
    async def _prev_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        await self._turn(interaction, self._page - 1)

    @discord.ui.button(label="Next ▶", style=discord.ButtonStyle.secondary)
    async def _next_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        await self._turn(interaction, self._page + 1)

    async def _turn(self, interaction: Interaction, new_page: int) -> None:
        embed, has_next = await self._fetch_page(new_page)
        self._page = new_page
        self._prev_btn.disabled = new_page == 0
        self._next_btn.disabled = not has_next
        await interaction.response.edit_message(embed=embed, view=self)

    async def on_timeout(self) -> None:
        disable_view_buttons(self)
        await safe_edit_view_message(self.bot_message, view=self)


class ServersView(discord.ui.View):
    def __init__(
        self,
        servers: LiveServerRegistry,
        *,
        sort_mode: Literal["players", "name"] = "players",
        locale: str = DEFAULT,
    ) -> None:
        super().__init__(timeout=180)
        self._servers = servers
        self._sort_mode: Literal["players", "name"] = sort_mode
        # The message is one for everyone who presses its buttons: it keeps the language
        # of the user who asked for it.
        self._locale = locale
        self.bot_message: discord.Message | None = None
        self._refresh_btn.label = t(locale, "servers-refresh")
        self._sync_sort_button_label()

    @property
    def sort_mode(self) -> Literal["players", "name"]:
        return self._sort_mode

    def build_embed(self) -> discord.Embed:
        servers = sort_live_servers(self._servers.get_all_servers(), self._sort_mode)
        return build_servers_embed(
            servers, sort_mode=self._sort_mode, locale=self._locale
        )

    @discord.ui.button(label="Refresh", style=discord.ButtonStyle.primary, emoji="🔄")
    async def _refresh_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        embed = self.build_embed()
        await interaction.response.edit_message(embed=embed, view=self)

    @discord.ui.button(label="Sort: players", style=discord.ButtonStyle.secondary)
    async def _sort_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        self._sort_mode = "name" if self._sort_mode == "players" else "players"
        self._sync_sort_button_label()
        embed = self.build_embed()
        await interaction.response.edit_message(embed=embed, view=self)

    async def on_timeout(self) -> None:
        disable_view_buttons(self)
        await safe_edit_view_message(self.bot_message, view=self)

    def _sync_sort_button_label(self) -> None:
        self._sort_btn.label = t(
            self._locale,
            "command-sort",
            sort=t(self._locale, f"servers-sort-{self._sort_mode}"),
        )


MapOption = tuple[str, str, str]
"""One map in the open menu: label, value and description."""


class MapsListView(discord.ui.View):
    """Pages, sort order and, when ``open_map`` is given, a menu that opens a map of the page."""

    def __init__(
        self,
        *,
        page: int,
        has_prev: bool,
        has_next: bool,
        sort_mode: MapSortMode,
        fetch_page: Callable[
            [int, MapSortMode], Awaitable[tuple[discord.Embed, bool, list[MapOption]]]
        ],
        locale: str = DEFAULT,
        options: list[MapOption] | None = None,
        open_map: Callable[[Interaction, str], Awaitable[None]] | None = None,
    ) -> None:
        super().__init__(timeout=120)
        self._page = page
        self._sort_mode: MapSortMode = sort_mode
        self._fetch_page = fetch_page
        self._locale = locale
        self._open_map = open_map
        self.bot_message: discord.Message | None = None
        self._prev_btn.label = t(locale, "command-page-previous")
        self._next_btn.label = t(locale, "command-page-next")
        self._prev_btn.disabled = not has_prev
        self._next_btn.disabled = not has_next
        self._sync_sort_button_label()
        self._open: discord.ui.Select | None = None
        if open_map is not None:
            self._open = discord.ui.Select(
                placeholder=t(locale, "maps-list-open"),
                min_values=1,
                max_values=1,
                row=1,
            )
            self._open.callback = self._on_open  # type: ignore[method-assign]
            self._set_options(options or [])
            self.add_item(self._open)

    def _set_options(self, options: list[MapOption]) -> None:
        if self._open is None:
            return
        self._open.options = [
            discord.SelectOption(
                label=label[:100] or "?",
                value=value,
                description=description[:100] or None,
            )
            for label, value, description in options[:25]
        ]
        self._open.disabled = not options

    async def _on_open(self, interaction: Interaction) -> None:
        if self._open is None or self._open_map is None or not self._open.values:
            return
        await self._open_map(interaction, self._open.values[0])

    @discord.ui.button(label="◀ Prev", style=discord.ButtonStyle.secondary)
    async def _prev_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        await self._turn(interaction, self._page - 1)

    @discord.ui.button(label="Next ▶", style=discord.ButtonStyle.secondary)
    async def _next_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        await self._turn(interaction, self._page + 1)

    @discord.ui.button(label="Sort: reputation", style=discord.ButtonStyle.secondary)
    async def _sort_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        self._sort_mode = self._next_sort_mode()
        self._sync_sort_button_label()
        embed, has_next, options = await self._fetch_page(0, self._sort_mode)
        self._page = 0
        self._prev_btn.disabled = True
        self._next_btn.disabled = not has_next
        self._set_options(options)
        await interaction.response.edit_message(embed=embed, view=self)

    async def _turn(self, interaction: Interaction, new_page: int) -> None:
        embed, has_next, options = await self._fetch_page(new_page, self._sort_mode)
        self._page = new_page
        self._prev_btn.disabled = new_page == 0
        self._next_btn.disabled = not has_next
        self._set_options(options)
        await interaction.response.edit_message(embed=embed, view=self)

    async def on_timeout(self) -> None:
        disable_view_buttons(self)
        if self._open is not None:
            self._open.disabled = True
        await safe_edit_view_message(self.bot_message, view=self)

    def _sync_sort_button_label(self) -> None:
        self._sort_btn.label = t(
            self._locale,
            "command-sort",
            sort=t(self._locale, f"maps-list-sort-{self._sort_mode}"),
        )

    def _next_sort_mode(self) -> MapSortMode:
        if self._sort_mode == "reputation":
            return "popularity"
        if self._sort_mode == "popularity":
            return "name"
        return "reputation"


class MapDetailsView(discord.ui.View):
    """The /map card's button that swaps the thumbnail for the detailed render.

    Anyone who sees the card may press it. ``render`` gets the id of whoever pressed it, draws
    the picture and returns the new card and file, or a message for that person when it could
    not (including when their render cooldown has not run out).
    """

    def __init__(
        self,
        *,
        render: Callable[[int], Awaitable[tuple[discord.Embed, discord.File] | str]],
        locale: str = DEFAULT,
    ) -> None:
        # The render can take a while; the interaction token outlives it (15 minutes).
        super().__init__(timeout=600)
        self._render = render
        self._locale = locale
        self.bot_message: discord.Message | None = None
        self._render_btn.label = t(locale, "maps-card-render-button")

    @discord.ui.button(label="Detailed render", style=discord.ButtonStyle.primary)
    async def _render_btn(
        self, interaction: Interaction, button: discord.ui.Button
    ) -> None:
        button.disabled = True
        await interaction.response.edit_message(view=self)
        result = await self._render(interaction.user.id)
        if isinstance(result, str):
            button.disabled = False
            await interaction.edit_original_response(view=self)
            await interaction.followup.send(result, ephemeral=True)
            return
        embed, file = result
        self.stop()
        await interaction.edit_original_response(
            embed=embed, attachments=[file], view=None
        )

    async def on_timeout(self) -> None:
        disable_view_buttons(self)
        await safe_edit_view_message(self.bot_message, view=self)
