package com.pdf2questao.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdf2questao.scoring.ScoringCalculator.GroupSpec;
import com.pdf2questao.scoring.ScoringCalculator.ProfileSpec;
import com.pdf2questao.scoring.ScoringCalculator.RuleSpec;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cria, le e converte perfis de pontuacao. O formato JSON de {@link #saveFromJson} e o mesmo do
 * perfil de exemplo {@link #EXAMPLE_NAME}, criado na subida se ainda nao existir:
 * <pre>{"name": "...", "description": "...", "minTotal": 116,
 *  "groups": [{"name": "P1", "minScore": 45, "rules": [
 *      {"subject": "Portugues", "aliases": ["Lingua Portuguesa"], "weight": 1, "questionCount": 10}]}]}</pre>
 */
@Service
public class ScoringProfileService implements ApplicationRunner {

    public static final String EXAMPLE_NAME = "SEFAZ-SP APOFP 2026 – C03";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String EXAMPLE_JSON = """
            {"name": "%s",
             "description": "P1: minimo 45 de 112. P2: minimo 48 de 120. Minimo total P1 + P2: 116.",
             "minTotal": 116,
             "groups": [
               {"name": "P1", "minScore": 45, "rules": [
                 {"subject": "Adm. Geral/P\\u00fablica/Auditoria", "aliases": ["Administra\\u00e7\\u00e3o Geral e P\\u00fablica", "Auditoria"], "weight": 1, "questionCount": 8},
                 {"subject": "Dir. Administrativo", "aliases": ["Direito Administrativo"], "weight": 1, "questionCount": 10},
                 {"subject": "Dir. Constitucional", "aliases": ["Direito Constitucional"], "weight": 1, "questionCount": 10},
                 {"subject": "Economia", "aliases": ["Economia e Finan\\u00e7as P\\u00fablicas"], "weight": 1, "questionCount": 4},
                 {"subject": "Portugu\\u00eas", "aliases": ["L\\u00edngua Portuguesa"], "weight": 1, "questionCount": 10},
                 {"subject": "Mat. Financeira/Estat\\u00edstica", "aliases": ["Matem\\u00e1tica Financeira/Estat\\u00edstica"], "weight": 1, "questionCount": 6},
                 {"subject": "Adm. Financeira e Or\\u00e7ament\\u00e1ria", "weight": 2, "questionCount": 8},
                 {"subject": "Contabilidade Geral e P\\u00fablica", "aliases": ["Contabilidade Geral"], "weight": 2, "questionCount": 8},
                 {"subject": "Direito Financeiro", "aliases": ["Finan\\u00e7as P\\u00fablicas", "Direito Financeiro e Finan\\u00e7as P\\u00fablicas"], "weight": 2, "questionCount": 8},
                 {"subject": "Flu\\u00eancia de Dados", "weight": 2, "questionCount": 8}]},
               {"name": "P2", "minScore": 48, "rules": [
                 {"subject": "Ci\\u00eancia de Dados e TI", "aliases": ["Tecnologia da Informa\\u00e7\\u00e3o e Comunica\\u00e7\\u00e3o"], "weight": 3, "questionCount": 40}]}
             ]}
            """.formatted(EXAMPLE_NAME);

    public static String exampleJson() {
        return EXAMPLE_JSON;
    }

    private final ScoringProfileRepository profileRepository;
    private final ScoringGroupRepository groupRepository;
    private final ScoringRuleRepository ruleRepository;

    public ScoringProfileService(ScoringProfileRepository profileRepository, ScoringGroupRepository groupRepository,
                                 ScoringRuleRepository ruleRepository) {
        this.profileRepository = profileRepository;
        this.groupRepository = groupRepository;
        this.ruleRepository = ruleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (profileRepository.findByName(EXAMPLE_NAME).isEmpty()) {
            saveFromJson(EXAMPLE_JSON);
        }
    }

    /**
     * Cria o perfil, ou substitui as regras do perfil que ja tem o mesmo nome (sessoes antigas
     * guardam o resultado congelado, entao nao mudam).
     *
     * @throws IllegalArgumentException com mensagem pronta para a tela.
     */
    @Transactional
    public ScoringProfile saveFromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Perfil nao e um JSON valido: " + e.getOriginalMessage());
        }
        if (root == null || !root.hasNonNull("name") || root.get("name").asText().isBlank()) {
            throw new IllegalArgumentException("O perfil precisa de um \"name\".");
        }
        JsonNode groups = root.get("groups");
        if (groups == null || !groups.isArray() || groups.isEmpty()) {
            throw new IllegalArgumentException("O perfil precisa de ao menos um grupo em \"groups\".");
        }

        ScoringProfile profile = profileRepository.findByName(root.get("name").asText().trim())
                .orElseGet(ScoringProfile::new);
        profile.setName(root.get("name").asText().trim());
        profile.setDescription(root.hasNonNull("description") ? root.get("description").asText() : null);
        profile.setMinTotal(root.hasNonNull("minTotal") ? root.get("minTotal").asDouble() : null);
        profile = profileRepository.save(profile);
        deleteChildren(profile.getId());

        int groupPosition = 0;
        for (JsonNode g : groups) {
            if (!g.hasNonNull("name") || !g.has("rules") || !g.get("rules").isArray()) {
                throw new IllegalArgumentException("Cada grupo precisa de \"name\" e \"rules\": " + g);
            }
            ScoringGroup group = new ScoringGroup();
            group.setProfileId(profile.getId());
            group.setName(g.get("name").asText());
            group.setMinScore(g.hasNonNull("minScore") ? g.get("minScore").asDouble() : null);
            group.setPosition(groupPosition++);
            group = groupRepository.save(group);

            int rulePosition = 0;
            for (JsonNode r : g.get("rules")) {
                if (!r.hasNonNull("subject") || !r.hasNonNull("weight")) {
                    throw new IllegalArgumentException("Cada regra precisa de \"subject\" e \"weight\": " + r);
                }
                ScoringRule rule = new ScoringRule();
                rule.setGroupId(group.getId());
                rule.setSubject(r.get("subject").asText().trim());
                if (r.has("aliases") && r.get("aliases").isArray()) {
                    List<String> aliases = new ArrayList<>();
                    r.get("aliases").forEach(a -> aliases.add(a.asText().trim()));
                    rule.setMatchSubjects(String.join("\n", aliases));
                }
                rule.setWeight(r.get("weight").asDouble());
                rule.setQuestionCount(r.hasNonNull("questionCount") ? r.get("questionCount").asInt() : null);
                rule.setPosition(rulePosition++);
                ruleRepository.save(rule);
            }
        }
        return profile;
    }

    private void deleteChildren(Long profileId) {
        List<Long> groupIds = groupRepository.findByProfileIdOrderByPosition(profileId).stream()
                .map(ScoringGroup::getId).toList();
        if (!groupIds.isEmpty()) {
            ruleRepository.deleteByGroupIdIn(groupIds);
        }
        groupRepository.deleteByProfileId(profileId);
    }

    public Optional<ProfileSpec> spec(Long profileId) {
        return profileRepository.findById(profileId).map(this::toSpec);
    }

    public ProfileSpec toSpec(ScoringProfile profile) {
        List<ScoringGroup> groups = groupRepository.findByProfileIdOrderByPosition(profile.getId());
        Map<Long, List<ScoringRule>> rules = ruleRepository
                .findByGroupIdInOrderByPosition(groups.stream().map(ScoringGroup::getId).toList()).stream()
                .collect(Collectors.groupingBy(ScoringRule::getGroupId));
        List<GroupSpec> groupSpecs = groups.stream().map(g -> new GroupSpec(g.getName(), g.getMinScore(),
                rules.getOrDefault(g.getId(), List.of()).stream().map(this::toSpec).toList())).toList();
        return new ProfileSpec(profile.getName(), profile.getMinTotal(), groupSpecs);
    }

    private RuleSpec toSpec(ScoringRule rule) {
        Set<String> aliases = rule.getMatchSubjects() == null ? Set.of()
                : Arrays.stream(rule.getMatchSubjects().split("\n")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new RuleSpec(rule.getSubject(), aliases, rule.getWeight(), rule.getQuestionCount());
    }

    public List<ScoringProfile> findAll() {
        return profileRepository.findAll();
    }

    public Optional<ScoringProfile> find(Long id) {
        return profileRepository.findById(id);
    }
}
