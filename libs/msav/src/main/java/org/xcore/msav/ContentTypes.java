package org.xcore.msav;

import java.util.List;

/**
 * Mindustry v160's {@code ContentType} ordinals, which save files store. The engine's enum says
 * "do not rearrange, ever"; the plugin's tests check this copy against it.
 */
public final class ContentTypes {
    public static final List<String> NAMES = List.of("item", "block", "mech_UNUSED", "bullet", "liquid", "status",
            "unit", "weather", "effect_UNUSED", "sector", "loadout_UNUSED", "typeid_UNUSED", "error", "planet",
            "ammo_UNUSED", "team", "unitCommand", "unitStance");
    public static final int COUNT = NAMES.size();
    public static final int BLOCK = 1;

    private ContentTypes() {}
}
