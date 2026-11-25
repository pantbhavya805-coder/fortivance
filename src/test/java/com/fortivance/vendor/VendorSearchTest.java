package com.fortivance.vendor;

import com.fortivance.common.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VendorSearchTest {

    @Test
    void defaultsWhenNothingGiven() {
        VendorSearch s = VendorSearch.of(null, null, null, null, null, null, null);
        assertThat(s.q()).isNull();
        assertThat(s.type()).isEqualTo("all");
        assertThat(s.page()).isZero();
        assertThat(s.size()).isEqualTo(25);
    }

    @Test
    void blankValuesCountAsNotGiven() {
        VendorSearch s = VendorSearch.of("  ", "", " ", "", "", 0, 10);
        assertThat(s.q()).isNull();
        assertThat(s.tier()).isNull();
        assertThat(s.scope()).isNull();
        assertThat(s.flag()).isNull();
    }

    @Test
    void choicesAreCaseInsensitive() {
        VendorSearch s = VendorSearch.of(" nimbus ", "critical", "in_scope", "DIRECT", "Overdue", 2, 50);
        assertThat(s.q()).isEqualTo("nimbus");
        assertThat(s.tier()).isEqualTo("CRITICAL");
        assertThat(s.scope()).isEqualTo("IN_SCOPE");
        assertThat(s.type()).isEqualTo("direct");
        assertThat(s.flag()).isEqualTo("overdue");
    }

    @Test
    void rejectsUnknownValuesAndBadPaging() {
        assertThatThrownBy(() -> VendorSearch.of(null, "SEVERE", null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("tier");
        assertThatThrownBy(() -> VendorSearch.of(null, null, "OUT", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("scope");
        assertThatThrownBy(() -> VendorSearch.of(null, null, null, "fourth", null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> VendorSearch.of(null, null, null, null, "late", null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("flag");
        assertThatThrownBy(() -> VendorSearch.of(null, null, null, null, null, -1, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("page");
        assertThatThrownBy(() -> VendorSearch.of(null, null, null, null, null, null, 0))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("size");
        assertThatThrownBy(() -> VendorSearch.of(null, null, null, null, null, null, 101))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("size");
        assertThatThrownBy(() -> VendorSearch.of("x".repeat(201), null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("q");
    }

    @Test
    void likeWildcardsTypedByTheUserAreLiteral() {
        assertThat(VendorSearch.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }

    @Test
    void pagesAreSortedByNameThenId() {
        Pageable p = VendorSearch.of(null, null, null, null, null, 3, 20).pageable();
        assertThat(p.getPageNumber()).isEqualTo(3);
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(p.getSort()).isEqualTo(Sort.by("name").ascending().and(Sort.by("id").ascending()));
    }

    @Test
    void pagesAnInMemoryList() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        PageResult<Integer> first = PageResult.of(all, 0, 6);
        assertThat(first.items()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(first.total()).isEqualTo(13);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(PageResult.of(all, 2, 6).items()).containsExactly(13);
        assertThat(PageResult.of(all, 5, 6).items()).isEmpty();
        assertThat(PageResult.of(List.of(), 0, 6).totalPages()).isZero();
    }
}
