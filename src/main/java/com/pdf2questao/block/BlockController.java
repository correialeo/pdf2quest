package com.pdf2questao.block;

import com.pdf2questao.importer.ImportJobRepository;
import com.pdf2questao.question.Passage;
import com.pdf2questao.question.PassageRepository;
import com.pdf2questao.question.Question;
import com.pdf2questao.scoring.ScoringProfile;
import com.pdf2questao.scoring.ScoringProfileService;
import com.pdf2questao.session.StudySession;
import com.pdf2questao.session.StudySessionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
public class BlockController {

    private static final int MIN_SOURCE_ROWS = 3;

    private final BlockService blockService;
    private final StudySessionService sessionService;
    private final ScoringProfileService profileService;
    private final ImportJobRepository importJobRepository;
    private final PassageRepository passageRepository;

    public BlockController(BlockService blockService, StudySessionService sessionService,
                           ScoringProfileService profileService, ImportJobRepository importJobRepository,
                           PassageRepository passageRepository) {
        this.blockService = blockService;
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.importJobRepository = importJobRepository;
        this.passageRepository = passageRepository;
    }

    @GetMapping("/blocos")
    public String list(Model model) {
        List<StudyBlock> blocks = blockService.findAll();
        Map<Long, Integer> totals = new LinkedHashMap<>();
        for (StudyBlock b : blocks) {
            totals.put(b.getId(), blockService.resolve(b.getId()).questions().size());
        }
        Map<Long, String> profileNames = new LinkedHashMap<>();
        profileService.findAll().forEach(p -> profileNames.put(p.getId(), p.getName()));
        model.addAttribute("blocks", blocks);
        model.addAttribute("totals", totals);
        model.addAttribute("profileNames", profileNames);
        model.addAttribute("jobs", importJobRepository.findAllByOrderByStartedAtDesc());
        model.addAttribute("suggested", blockService.suggestedDiagnosticJobs());
        return "blocos";
    }

    @GetMapping("/blocos/novo")
    public String create(Model model) {
        fillForm(model, null, "", "", "", null, padded(List.of()), "");
        return "bloco-form";
    }

    @GetMapping("/blocos/{id}/editar")
    public String edit(@PathVariable Long id, Model model) {
        StudyBlock block = blockService.find(id).orElseThrow();
        List<BlockService.SourceForm> sources = blockService.sources(id).stream().map(s -> new BlockService.SourceForm(
                s.getImportJobId() == null ? "" : s.getImportJobId().toString(), nz(s.getRanges()), nz(s.getSubject()),
                nz(s.getTopic()), nz(s.getOrganization()), s.getYear() == null ? "" : s.getYear().toString(),
                s.getMaxQuestions() == null ? "" : s.getMaxQuestions().toString(), s.isShuffle())).toList();
        fillForm(model, id, block.getName(), nz(block.getDescription()),
                block.getTimeLimitMinutes() == null ? "" : block.getTimeLimitMinutes().toString(),
                block.getScoringProfileId(), padded(sources), blockService.exclusionText(blockService.exclusions(id)));
        return "bloco-form";
    }

    @PostMapping("/blocos/salvar")
    public String save(@RequestParam(required = false) Long id, @RequestParam String name,
                       @RequestParam(defaultValue = "") String description,
                       @RequestParam(defaultValue = "") String timeLimit,
                       @RequestParam(required = false) Long profileId,
                       @RequestParam(defaultValue = "") List<String> srcJob,
                       @RequestParam(defaultValue = "") List<String> srcRanges,
                       @RequestParam(defaultValue = "") List<String> srcSubject,
                       @RequestParam(defaultValue = "") List<String> srcTopic,
                       @RequestParam(defaultValue = "") List<String> srcOrg,
                       @RequestParam(defaultValue = "") List<String> srcYear,
                       @RequestParam(defaultValue = "") List<String> srcMax,
                       @RequestParam(defaultValue = "") List<String> srcShuffle,
                       @RequestParam(defaultValue = "") String exclusions,
                       Model model, RedirectAttributes redirect) {
        List<BlockService.SourceForm> sources = sourceForms(srcJob, srcRanges, srcSubject, srcTopic, srcOrg, srcYear,
                srcMax, srcShuffle);
        try {
            StudyBlock saved = blockService.save(blockService.draft(id, name, description, timeLimit, profileId,
                    sources, exclusions));
            redirect.addFlashAttribute("message", "Bloco salvo.");
            return "redirect:/blocos/" + saved.getId();
        } catch (IllegalArgumentException e) {
            fillForm(model, id, name, description, timeLimit, profileId, padded(sources), exclusions);
            model.addAttribute("error", e.getMessage());
            return "bloco-form";
        }
    }

    /** Pre-visualizacao do que esta no formulario, sem salvar. */
    @PostMapping("/blocos/preview")
    public String preview(@RequestParam(required = false) Long id, @RequestParam String name,
                          @RequestParam(defaultValue = "") String description,
                          @RequestParam(defaultValue = "") String timeLimit,
                          @RequestParam(required = false) Long profileId,
                          @RequestParam(defaultValue = "") List<String> srcJob,
                          @RequestParam(defaultValue = "") List<String> srcRanges,
                          @RequestParam(defaultValue = "") List<String> srcSubject,
                          @RequestParam(defaultValue = "") List<String> srcTopic,
                          @RequestParam(defaultValue = "") List<String> srcOrg,
                          @RequestParam(defaultValue = "") List<String> srcYear,
                          @RequestParam(defaultValue = "") List<String> srcMax,
                          @RequestParam(defaultValue = "") List<String> srcShuffle,
                          @RequestParam(defaultValue = "") String exclusions,
                          Model model) {
        List<BlockService.SourceForm> sources = sourceForms(srcJob, srcRanges, srcSubject, srcTopic, srcOrg, srcYear,
                srcMax, srcShuffle);
        fillForm(model, id, name, description, timeLimit, profileId, padded(sources), exclusions);
        try {
            // O nome pode estar vazio so para ver a previa: usa um provisorio.
            model.addAttribute("preview", blockService.preview(blockService.draft(id, name.isBlank() ? "(previa)" : name,
                    description, timeLimit, profileId, sources, exclusions)));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "bloco-form";
    }

    @GetMapping("/blocos/{id}")
    public String detail(@PathVariable Long id, Model model) {
        StudyBlock block = blockService.find(id).orElseThrow();
        model.addAttribute("block", block);
        BlockService.Preview preview = blockService.preview(id);
        model.addAttribute("preview", preview);
        model.addAttribute("passages", passageRepository.findAllById(preview.questions().stream()
                        .map(Question::getPassageId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Passage::getId, p -> p)));
        model.addAttribute("profile", block.getScoringProfileId() == null ? null
                : profileService.find(block.getScoringProfileId()).map(ScoringProfile::getName).orElse(null));
        model.addAttribute("exclusions", blockService.exclusionText(blockService.exclusions(id)));
        return "bloco-detalhe";
    }

    @PostMapping("/blocos/{id}/iniciar")
    public String start(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            StudySession session = sessionService.startBloco(id);
            return "redirect:/resolver/" + session.getId() + "?index=0";
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/blocos/" + id;
        }
    }

    @PostMapping("/blocos/{id}/excluir")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        blockService.delete(id);
        redirect.addFlashAttribute("message", "Bloco apagado (as questoes e sessoes continuam no banco).");
        return "redirect:/blocos";
    }

    @PostMapping("/blocos/diagnostico")
    public String diagnostic(@RequestParam(required = false) Long p1, @RequestParam(required = false) Long p2,
                             @RequestParam(required = false) Long p3, RedirectAttributes redirect) {
        redirect.addFlashAttribute("message", blockService.createDiagnosticBlocks(p1, p2, p3));
        return "redirect:/blocos";
    }

    @PostMapping("/import/{id}/bloco")
    public String blockForProva(@PathVariable Long id, RedirectAttributes redirect) {
        StudyBlock block = blockService.createBlockForProva(id);
        redirect.addFlashAttribute("message", "Bloco criado: " + block.getName());
        return "redirect:/blocos/" + block.getId();
    }

    @GetMapping("/perfis")
    public String profiles(Model model) {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (ScoringProfile p : profileService.findAll()) {
            profiles.add(Map.of("profile", p, "spec", profileService.toSpec(p)));
        }
        model.addAttribute("profiles", profiles);
        model.addAttribute("exampleJson", ScoringProfileService.exampleJson());
        return "perfis";
    }

    @PostMapping("/perfis")
    public String saveProfile(@RequestParam String json, RedirectAttributes redirect) {
        try {
            ScoringProfile saved = profileService.saveFromJson(json);
            redirect.addFlashAttribute("message", "Perfil '" + saved.getName() + "' salvo.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            redirect.addFlashAttribute("json", json);
        }
        return "redirect:/perfis";
    }

    private void fillForm(Model model, Long id, String name, String description, String timeLimit, Long profileId,
                          List<BlockService.SourceForm> sources, String exclusions) {
        model.addAttribute("blockId", id);
        model.addAttribute("name", name);
        model.addAttribute("description", description);
        model.addAttribute("timeLimit", timeLimit);
        model.addAttribute("profileId", profileId);
        model.addAttribute("sources", sources);
        model.addAttribute("exclusions", exclusions);
        model.addAttribute("jobs", importJobRepository.findAllByOrderByStartedAtDesc());
        model.addAttribute("profiles", profileService.findAll());
    }

    private List<BlockService.SourceForm> sourceForms(List<String> job, List<String> ranges, List<String> subject,
                                                       List<String> topic, List<String> org, List<String> year,
                                                       List<String> max, List<String> shuffle) {
        List<BlockService.SourceForm> forms = new ArrayList<>();
        for (int i = 0; i < job.size(); i++) {
            forms.add(new BlockService.SourceForm(at(job, i), at(ranges, i), at(subject, i), at(topic, i), at(org, i),
                    at(year, i), at(max, i), "true".equals(at(shuffle, i))));
        }
        return forms;
    }

    private List<BlockService.SourceForm> padded(List<BlockService.SourceForm> sources) {
        List<BlockService.SourceForm> rows = new ArrayList<>(sources);
        while (rows.size() < MIN_SOURCE_ROWS) {
            rows.add(new BlockService.SourceForm("", "", "", "", "", "", "", false));
        }
        return rows;
    }

    private static String at(List<String> list, int i) {
        return i < list.size() ? list.get(i) : "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
