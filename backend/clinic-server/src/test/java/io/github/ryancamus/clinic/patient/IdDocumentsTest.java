package io.github.ryancamus.clinic.patient;

import io.github.ryancamus.clinic.patient.domain.IdDocuments;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IdDocumentsTest {

    @Test
    void validatesGb11643CheckDigitAndExtractsBirthAndGender() {
        // GB 11643-1999 附录中的示例号码
        var e = IdDocuments.validate(IdDocuments.RESIDENT_ID, "11010519491231002X").orElseThrow();
        assertThat(e.birthDate()).isEqualTo(LocalDate.of(1949, 12, 31));
        assertThat(e.gender()).isEqualTo(2);
        var m = IdDocuments.validate(IdDocuments.RESIDENT_ID, IdDocuments.normalize(" 999999 2004 0501 0018 ")).orElseThrow();
        assertThat(m.gender()).isEqualTo(1);
    }

    @Test
    void rejectsWrongCheckDigitImpossibleDateAndBadFormat() {
        assertThatThrownBy(() -> IdDocuments.validate(IdDocuments.RESIDENT_ID, "110105194912310021"))
                .isInstanceOf(IdDocuments.InvalidIdException.class);
        // 2 月 30 日
        assertThatThrownBy(() -> IdDocuments.validate(IdDocuments.RESIDENT_ID, "11010520010230002" + check("11010520010230002")))
                .isInstanceOf(IdDocuments.InvalidIdException.class);
        assertThatThrownBy(() -> IdDocuments.validate(IdDocuments.RESIDENT_ID, "1101051949"))
                .isInstanceOf(IdDocuments.InvalidIdException.class);
        assertThatThrownBy(() -> IdDocuments.validate(IdDocuments.PASSPORT, "E1"))
                .isInstanceOf(IdDocuments.InvalidIdException.class);
    }

    @Test
    void passportsAndPermitsHaveNoEmbeddedData() {
        assertThat(IdDocuments.validate(IdDocuments.PASSPORT, IdDocuments.normalize("e12345678"))).isEmpty();
        assertThat(IdDocuments.validate(IdDocuments.HMT_PERMIT, "H12345678")).isEmpty();
    }

    @Test
    void recognisesMainlandMobileNumbers() {
        assertThat(IdDocuments.isMobile("13800138000")).isTrue();
        assertThat(IdDocuments.isMobile("12800138000")).isFalse();
        assertThat(IdDocuments.isMobile("1380013800")).isFalse();
    }

    public static char check(String body17) {
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (body17.charAt(i) - '0') * w[i];
        }
        return "10X98765432".charAt(sum % 11);
    }
}
