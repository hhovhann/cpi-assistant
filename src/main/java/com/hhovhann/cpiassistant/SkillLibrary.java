package com.hhovhann.cpiassistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skills: playbooks for recurring kinds of question, as Markdown in
 * {@code resources/skills}. Each file starts with a name and a one-line
 * description. The {@code loadSkill} tool's description lists only those
 * lines ({@link AgentTools#addSkills}); the model loads a full playbook when a
 * question needs it — so the request stays short however many skills there
 * are. The same idea as skills in Claude Code.
 */
@Component
public class SkillLibrary {

    private static final Pattern FRONT_MATTER =
            Pattern.compile("(?s)^---\\s*\\nname:\\s*(\\S+)\\s*\\ndescription:\\s*(.+?)\\s*\\n---\\s*\\n(.*)$");

    record Skill(String name, String description, String body) {
    }

    private final List<Skill> skills;

    public SkillLibrary() {
        this(load());
    }

    SkillLibrary(List<Skill> skills) {
        this.skills = skills;
    }

    private static List<Skill> load() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:skills/*.md");
            return Arrays.stream(files).map(SkillLibrary::parse).sorted(Comparator.comparing(Skill::name)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the skills", e);
        }
    }

    static Skill parse(Resource file) {
        try {
            return parse(file.getFilename(), file.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read skill " + file.getFilename(), e);
        }
    }

    static Skill parse(String file, String text) {
        Matcher m = FRONT_MATTER.matcher(text.replace("\r\n", "\n"));
        if (!m.matches()) {
            throw new IllegalStateException("Skill " + file + " needs a front matter with name and description");
        }
        return new Skill(m.group(1), m.group(2), m.group(3).strip());
    }

    /** One line per skill, for the loadSkill tool's description. */
    public String summary() {
        return String.join("\n", skills.stream().map(s -> "- " + s.name() + ": " + s.description()).toList());
    }

    @Tool("""
            Loads a skill: step-by-step instructions for a kind of question. When the question \
            matches a skill below, call this first, then follow the instructions.""")
    public String loadSkill(@P("The skill name, exactly as listed") String name) {
        return skills.stream().filter(s -> s.name().equals(name.strip())).findFirst()
                .map(s -> "Skill " + s.name() + ":\n" + s.body())
                .orElse("No skill named \"" + name + "\". Skills: "
                        + String.join(", ", skills.stream().map(Skill::name).toList()));
    }
}
