package org.xcore.plugin.service.map.inspect;

import mindustry.ctype.ContentType;
import org.junit.jupiter.api.Test;
import org.xcore.msav.ContentRegistrySnapshot;
import org.xcore.msav.ContentTypes;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTypesTest {
    @Test
    void libraryCopyMatchesTheEngine() {
        assertThat(ContentTypes.NAMES).containsExactlyElementsOf(Arrays.stream(ContentType.all).map(Enum::name).toList());
        assertThat(ContentRegistrySnapshot.BLOCK_TYPE).isEqualTo(ContentType.block.ordinal());
    }
}
