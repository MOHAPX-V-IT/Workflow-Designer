package app.process;

import static app.process.Blueprint.*;

import app.process.Accounts.Person;
import app.process.Accounts.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class Pages {
    private final Accounts accounts;
    private final WorkflowService workflow;
    private final DateTimeFormatter date;
    private final boolean demo;

    Pages(Accounts accounts, WorkflowService workflow, @Value("${process.zone}") String zone,
          @Value("${process.demo}") boolean demo) {
        this.accounts = accounts;
        this.workflow = workflow;
        this.demo = demo;
        this.date = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.of(zone));
    }

    @ModelAttribute("actor")
    Person actor(Principal principal) {
        return principal == null ? null : accounts.byEmail(principal.getName());
    }

    @ModelAttribute("dates")
    ClockLabel dates() {
        return new ClockLabel();
    }

    public final class ClockLabel {
        public String label(OffsetDateTime time) { return time == null ? "—" : date.format(time); }
    }

    @GetMapping("/login")
    String login(Model model) {
        model.addAttribute("demo", demo);
        return "login";
    }

    @GetMapping("/")
    String dashboard(@ModelAttribute("actor") Person actor, Model model) {
        var inbox = workflow.inbox(actor);
        var mine = workflow.mine(actor);
        model.addAttribute("inbox", inbox.stream().limit(5).toList());
        model.addAttribute("inboxCount", inbox.size());
        model.addAttribute("overdueCount", inbox.stream().filter(WorkflowService.Run::overdue).count());
        model.addAttribute("mineCount", mine.size());
        model.addAttribute("published", workflow.published());
        return "dashboard";
    }

    @GetMapping("/inbox")
    String inbox(@ModelAttribute("actor") Person actor, Model model) {
        return runs(model, "Входящие", "Заявки, назначенные вам или вашей роли.", "inbox", workflow.inbox(actor));
    }

    @GetMapping("/requests")
    String requests(@ModelAttribute("actor") Person actor, @RequestParam(defaultValue = "mine") String view, Model model) {
        return "journal".equals(view)
            ? runs(model, "Журнал заявок", "Заявки с историей решений и выполнения.", "journal", workflow.journal(actor))
            : runs(model, "Мои заявки", "Статус и ответственные по вашим заявкам.", "mine", workflow.mine(actor));
    }

    private static String runs(Model model, String title, String description, String section, List<WorkflowService.Run> runs) {
        model.addAttribute("title", title);
        model.addAttribute("description", description);
        model.addAttribute("section", section);
        model.addAttribute("runs", runs);
        return "runs";
    }

    @GetMapping("/templates")
    String catalog(Model model) {
        model.addAttribute("published", workflow.published());
        return "catalog";
    }

    @GetMapping("/start/{id}")
    String start(@PathVariable long id, @RequestParam int version, @ModelAttribute("actor") Person actor, Model model) {
        var snapshot = workflow.published(id, version);
        return startForm(model, snapshot, workflow.issueLaunch(actor, snapshot), Map.of());
    }

    @PostMapping("/start/{id}")
    String launch(@PathVariable long id, @RequestParam int version, @RequestParam UUID nonce,
                  @ModelAttribute("actor") Person actor, HttpServletRequest request, HttpServletResponse response,
                  Model model, RedirectAttributes flash) {
        var snapshot = workflow.published(id, version);
        var values = new HashMap<String, String>();
        for (Field field : snapshot.definition().fields()) values.put(field.key(), request.getParameter("value_" + field.key()));
        try {
            var run = workflow.launch(actor, snapshot, nonce, values);
            flash.addFlashAttribute("notice", "Заявка запущена.");
            return "redirect:/requests/" + run.id();
        } catch (Problem problem) {
            if (!problem.status.is4xxClientError()) throw problem;
            response.setStatus(problem.status.value());
            model.addAttribute("error", problem.getMessage());
            return startForm(model, snapshot, nonce, values);
        }
    }

    private static String startForm(Model model, WorkflowService.Published snapshot, UUID nonce, Map<String, String> values) {
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("nonce", nonce);
        model.addAttribute("values", values);
        return "start";
    }

    @GetMapping("/requests/{id}")
    String details(@PathVariable long id, @ModelAttribute("actor") Person actor, Model model) {
        var detail = workflow.detail(actor, id);
        model.addAttribute("detail", detail);
        var route = new java.util.ArrayList<Integer>();
        var run = detail.run();
        for (int next = nextStep(run.definition(), run.values(), -1); next >= 0;
                next = nextStep(run.definition(), run.values(), next)) route.add(next);
        model.addAttribute("reachable", route);
        return "request";
    }

    @PostMapping("/requests/{id}/advance")
    String advance(@PathVariable long id, @RequestParam long version, @RequestParam Action decision,
                   @RequestParam(required = false) String note, @ModelAttribute("actor") Person actor, RedirectAttributes flash) {
        try {
            workflow.advance(actor, id, version, decision, note);
            flash.addFlashAttribute("notice", "Решение сохранено.");
        } catch (Problem problem) {
            if (problem.status.isSameCodeAs(org.springframework.http.HttpStatus.FORBIDDEN)
                    || problem.status.isSameCodeAs(org.springframework.http.HttpStatus.NOT_FOUND)) throw problem;
            flash.addFlashAttribute("error", problem.getMessage());
        }
        return "redirect:/requests/" + id;
    }

    @GetMapping("/admin/templates")
    String templates(@ModelAttribute("actor") Person actor, Model model) {
        model.addAttribute("templates", workflow.templates(actor));
        return "admin-templates";
    }

    @GetMapping("/admin/templates/new")
    String newTemplate(Model model) {
        return editor(model, null, 0, EditorForm.starter(), List.of());
    }

    @GetMapping("/admin/templates/{id}")
    String editTemplate(@PathVariable long id, @ModelAttribute("actor") Person actor, Model model) {
        var current = workflow.template(actor, id);
        return editor(model, id, current.revision(), current.definition(), workflow.versions(actor, id));
    }

    @PostMapping({"/admin/templates/new", "/admin/templates/{id}"})
    String saveTemplate(@PathVariable(required = false) Long id, @RequestParam int revision, @RequestParam String save,
                        @ModelAttribute("actor") Person actor, HttpServletRequest request, Model model, RedirectAttributes flash) {
        Definition draft = id == null ? EditorForm.starter() : workflow.template(actor, id).definition();
        try {
            draft = EditorForm.parse(request);
            boolean publish = "publish".equals(save);
            var saved = workflow.save(actor, id, revision, draft, publish);
            flash.addFlashAttribute("notice", publish ? "Опубликована версия " + saved.published() + "." : "Черновик сохранён.");
            return "redirect:/admin/templates/" + saved.id();
        } catch (Problem problem) {
            model.addAttribute("error", problem.getMessage());
            return editor(model, id, revision, draft, id == null ? List.of() : workflow.versions(actor, id));
        }
    }

    private String editor(Model model, Long id, int revision, Definition definition, List<WorkflowService.Published> versions) {
        model.addAttribute("templateId", id);
        model.addAttribute("revision", revision);
        model.addAttribute("definition", definition);
        model.addAttribute("versions", versions);
        model.addAttribute("people", accounts.all());
        model.addAttribute("roles", Role.values());
        model.addAttribute("fieldTypes", FieldType.values());
        model.addAttribute("stepKinds", StepKind.values());
        model.addAttribute("operators", Operator.values());
        model.addAttribute("blankField", new Field("", "", FieldType.TEXT, false, List.of()));
        model.addAttribute("blankStep", new Step("", StepKind.WORK, "", 24, null));
        return "editor";
    }

    @GetMapping("/admin/people")
    String people(Model model) {
        model.addAttribute("people", accounts.all());
        model.addAttribute("roles", Role.values());
        return "people";
    }

    @PostMapping("/admin/people")
    String account(@RequestParam String name, @RequestParam String email, @RequestParam String password,
                   @RequestParam Role role, RedirectAttributes flash) {
        try {
            accounts.create(name, email, password, role);
            flash.addFlashAttribute("notice", "Сотрудник добавлен.");
        } catch (Problem problem) {
            flash.addFlashAttribute("error", problem.getMessage());
        }
        return "redirect:/admin/people";
    }

    @ExceptionHandler(Problem.class)
    ModelAndView onProblem(Problem problem, Principal principal) {
        var view = new ModelAndView("problem");
        view.setStatus(problem.status);
        view.addObject("actor", actor(principal));
        view.addObject("message", problem.getMessage());
        view.addObject("status", problem.status.value());
        return view;
    }
}
