package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;

class ConfirmationCodesTest {

    @Test
    void generatedCodesMatchThePattern() {
        Random random = new Random(1234L);

        for (int i = 0; i < 200; i++) {
            assertThat(ConfirmationCodes.generate(random)).matches(ConfirmationCodes.PATTERN);
        }
    }

    @Test
    void generatedCodesNeverUseCharactersThatCanBeMisread() {
        Random random = new Random(4321L);

        for (int i = 0; i < 200; i++) {
            String generated = ConfirmationCodes.generate(random).substring("INN-".length());

            assertThat(generated).doesNotContain("0", "O", "1", "I");
        }
    }

    @Test
    void aGeneratedCodeIsThePrefixPlusSixCharacters() {
        String code = ConfirmationCodes.generate(new Random(7L));

        assertThat(code).hasSize(10).startsWith("INN-");
        assertThat(code.substring("INN-".length())).hasSize(6);
    }

    @Test
    void differentSeedsProduceDifferentCodes() {
        String first = ConfirmationCodes.generate(new Random(1L));
        String second = ConfirmationCodes.generate(new Random(2L));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aCodeWithADigitOutsideTheAlphabetIsNotValid() {
        assertThat(ConfirmationCodes.isValid("INN-ABC123")).isFalse();
    }

    @Test
    void aWellFormedCodeIsValid() {
        assertThat(ConfirmationCodes.isValid("INN-ABC234")).isTrue();
    }

    @Test
    void aMissingCodeIsNotValid() {
        assertThat(ConfirmationCodes.isValid(null)).isFalse();
        assertThat(ConfirmationCodes.isValid("")).isFalse();
    }
}
