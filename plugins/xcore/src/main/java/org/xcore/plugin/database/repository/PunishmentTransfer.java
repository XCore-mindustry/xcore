package org.xcore.plugin.database.repository;

import org.jspecify.annotations.Nullable;

/**
 * An account merge moving a ban or a mute from the account it closes to the one that survives, as
 * protocol/storage/merges/README.md defines it.
 *
 * @param sourceUuid   the closed account's uuid before the merge
 * @param sourcePid    the closed account's PID, named in the copy's reason
 * @param targetUuid   the surviving account's uuid
 * @param survivingPid the surviving account's PID; null when it has none
 * @param targetName   the surviving account's nickname
 */
public record PunishmentTransfer(String sourceUuid, int sourcePid, String targetUuid, @Nullable Integer survivingPid,
                                 String targetName) {
}
