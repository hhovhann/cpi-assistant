package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillLibraryTest {

    @Test
    void theRealSkillsLoadAndAreListedInOneLineEach() {
        var skills = new SkillLibrary();

        assertThat(skills.summary().lines()).hasSize(3)
                .anyMatch(line -> line.startsWith("- troubleshoot-failed-message: "))
                .anyMatch(line -> line.startsWith("- configure-adapter: "))
                .anyMatch(line -> line.startsWith("- check-tenant-health: "));
        assertThat(skills.loadSkill("troubleshoot-failed-message")).startsWith("Skill troubleshoot-failed-message:\n1. Call getProblemMessages");
    }

    @Test
    void anUnknownSkillNamesTheOnesThatExist() {
        assertThat(new SkillLibrary().loadSkill("fix-everything"))
                .startsWith("No skill named \"fix-everything\". Skills: ")
                .contains("configure-adapter");
    }

    @Test
    void aSkillWithoutNameAndDescriptionIsRejected() {
        assertThatThrownBy(() -> SkillLibrary.parse("bad.md", "Just text."))
                .hasMessageContaining("needs a front matter");
    }
}
