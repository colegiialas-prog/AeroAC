package ac.grim.grimac.platform.bukkit.admin.training;

import ac.grim.grimac.neural.admin.AdminPermissions;

import ac.grim.grimac.neural.admin.AdminStyle;
import ac.grim.grimac.neural.admin.training.EvaluationSummary;
import ac.grim.grimac.neural.admin.training.ModelComparison;
import ac.grim.grimac.neural.admin.training.TrainingJob;
import ac.grim.grimac.neural.admin.training.TrainingServiceClient;
import ac.grim.grimac.neural.inference.FeatureEncoder;
import ac.grim.grimac.platform.bukkit.admin.AeroMenu;
import ac.grim.grimac.platform.bukkit.admin.BukkitAdminGui;
import ac.grim.grimac.platform.bukkit.admin.MenuItems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The training job and the evaluation behind it, or an honest statement that there is neither.
 *
 * <p>Two things this screen will not do. It will not train: there is no PyTorch in this process and
 * no subprocess, because a training run is GPU-bound and hours long and a Minecraft server is a
 * loop that must not stall. And it will not deploy: the furthest status this plugin recognises is
 * CANDIDATE, so a model that finished training and scored well is still a model somebody has to
 * decide to use.
 *
 * <p>Missing metrics render as INSUFFICIENT DATA or UNMEASURABLE, never as zero. A detection rate
 * of zero means the model caught nothing; a missing detection rate means nobody measured it. On a
 * screen about whether an anticheat works, collapsing those two into one number is the worst
 * available mistake.
 */
public final class ModelMenu extends AeroMenu {
    private static final int SLOT_JOB = 11;
    private static final int SLOT_CURRENT = 13;
    private static final int SLOT_CANDIDATE = 15;
    private static final int SLOT_COMPARE = 31;

    private final boolean comparison;

    public ModelMenu(BukkitAdminGui gui, Player viewer, boolean comparison) {
        super(gui, viewer);
        this.comparison = comparison;
    }

    @Override protected String title() {
        return MenuItems.HEADER + "AERO › " + (comparison ? "MODEL COMPARISON" : "MODEL TRAINING");
    }

    @Override public String permission() { return AdminPermissions.TRAINING_MODEL; }

    @Override protected int rows() { return 5; }

    @Override protected void draw(Inventory inventory) {
        TrainingServiceClient training = service().training();
        if (comparison) {
            drawComparison(inventory, training);
        } else {
            drawJob(inventory, training);
        }
        footer(inventory, this::back, comparison ? "model training" : "the training centre");
    }

    private void drawJob(Inventory inventory, TrainingServiceClient training) {
        TrainingJob job = training.job();
        List<String> lore = new ArrayList<>();
        lore.add(MenuItems.line("Status", job.status().name(),
                switch (job.status()) {
                    case FAILED -> MenuItems.BAD;
                    case TRAINING, EVALUATING, QUEUED -> MenuItems.WARN;
                    case TRAINED, EVALUATED, CANDIDATE -> MenuItems.GOOD;
                    default -> MenuItems.MUTED;
                }));
        if (job.configured()) {
            lore.add(MenuItems.line("Job", AdminStyle.blankIfEmpty(job.jobId(), "-")));
            lore.add(MenuItems.line("Model", AdminStyle.blankIfEmpty(job.modelType(), "-")));
            lore.add(MenuItems.line("Dataset", AdminStyle.blankIfEmpty(job.datasetVersion(), "-")));
            lore.add(MenuItems.line("Feature schema", job.featureSchemaVersion() > 0
                    ? "v" + job.featureSchemaVersion() : AdminStyle.NO_DATA));
            lore.add(MenuItems.line("Window", AdminStyle.blankIfEmpty(job.window(), "-")));
            lore.add(MenuItems.line("Heads", AdminStyle.blankIfEmpty(job.heads(), "-")));
            lore.add("");
            if (job.totalEpochs() > 0) {
                lore.add(MenuItems.line("Epoch", job.epoch() + " / " + job.totalEpochs()));
            }
            lore.add(MenuItems.LABEL + "Progress: " + MenuItems.VALUE
                    + (Double.isNaN(job.progress()) ? AdminStyle.NO_DATA
                            : Math.round(job.progress() * 100) + "%"));
            if (!Double.isNaN(job.progress())) {
                lore.add(MenuItems.GOOD + AdminStyle.bar(job.progress(), 20));
            }
            lore.add(MenuItems.line("Train loss", AdminStyle.number(job.trainLoss(), 4)));
            lore.add(MenuItems.line("Validation loss", AdminStyle.number(job.validationLoss(), 4)));
            lore.add(MenuItems.line("Elapsed", AdminStyle.duration(job.elapsedSeconds())));
        }
        if (job.message() != null) {
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED, job.message(), 44));
        }
        lore.add("");
        lore.add(MenuItems.line("Server feature schema", "v" + FeatureEncoder.FEATURE_SCHEMA_VERSION));
        inventory.setItem(SLOT_JOB, MenuItems.item(
                job.configured() ? Material.BEACON : Material.GRAY_DYE,
                MenuItems.HEADER + "TRAINING JOB", lore));

        inventory.setItem(SLOT_CURRENT, evaluation("CURRENT · IMPORTED REPORT", training.currentEvaluation(),
                Material.ENDER_EYE));
        inventory.setItem(SLOT_CANDIDATE, evaluation("CANDIDATE", training.candidateEvaluation(),
                Material.ENDER_PEARL));

        inventory.setItem(SLOT_COMPARE, MenuItems.item(Material.COMPARATOR,
                MenuItems.HEADER + "COMPARE",
                MenuItems.note("Current against candidate, one metric per row,"),
                MenuItems.note("with regressions called out."),
                MenuItems.note("Reports do not identify the server's active weights.")));

        inventory.setItem(29, MenuItems.item(Material.BARRIER, MenuItems.HEADER + "NO AUTOMATIC DEPLOY",
                MenuItems.wrap(MenuItems.MUTED,
                        "Finishing training changes nothing on this server. It does not swap the "
                                + "running model, enable mitigation or move a risk threshold. The "
                                + "furthest a model gets from here is CANDIDATE.", 44)));

        inventory.setItem(33, MenuItems.item(Material.CLOCK, MenuItems.HEADER + "REFRESH",
                MenuItems.note("Asks the backend for a new snapshot."),
                MenuItems.note("Returns immediately; nothing waits on the network.")));
    }

    private ItemStack evaluation(String name, EvaluationSummary evaluation, Material material) {
        List<String> lore = new ArrayList<>();
        if (!evaluation.present()) {
            lore.add(MenuItems.MUTED + "INSUFFICIENT DATA");
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                    "No evaluation has been imported for this model. Run the Python evaluation and "
                            + "import its report; nothing here is measured on the server.", 44));
            return MenuItems.item(Material.GRAY_DYE, MenuItems.HEADER + name, lore);
        }
        lore.add(MenuItems.line("Model", AdminStyle.blankIfEmpty(evaluation.modelVersion(), "-")));
        lore.add(MenuItems.line("Dataset", AdminStyle.blankIfEmpty(evaluation.datasetVersion(), "-")));
        lore.add(MenuItems.line("Feature schema", evaluation.featureSchemaVersion() > 0
                ? "v" + evaluation.featureSchemaVersion() : AdminStyle.NO_DATA));
        lore.add(MenuItems.line("Population", evaluation.population().name()));
        lore.add(MenuItems.line("Calibration", evaluation.calibration().name(),
                evaluation.calibration() == EvaluationSummary.Calibration.CALIBRATED
                        ? MenuItems.GOOD : MenuItems.WARN));
        lore.add("");
        if (evaluation.empty()) {
            lore.add(MenuItems.MUTED + "UNMEASURABLE");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                    "The report carried no usable metrics. Nothing is substituted for them.", 44));
            return MenuItems.item(material, MenuItems.HEADER + name, lore);
        }
        lore.add(metric("ROC-AUC", evaluation.rocAuc(), 3, ""));
        lore.add(metric("PR-AUC", evaluation.prAuc(), 3, ""));
        lore.add(metric("Detection at FPR " + AdminStyle.percent(evaluation.configuredFpr()),
                evaluation.tprAtFpr(), 3, ""));
        lore.add(metric("False positives / combat hour",
                evaluation.falsePositivesPerCombatHour(), 3, ""));
        lore.add(metric("Median detection time", evaluation.medianDetectionSeconds(), 1, "s"));
        appendBreakdown(lore, "Session SUSPICIOUS: known/unknown", evaluation.detectionByPopulation());
        appendBreakdown(lore, "By assist strength", evaluation.detectionByAssist());
        appendBreakdown(lore, "By client family", evaluation.detectionByClient());
        appendBreakdown(lore, "By scenario", evaluation.detectionByScenario());
        for (String caveat : evaluation.caveats()) {
            lore.addAll(MenuItems.wrap(MenuItems.WARN, caveat, 44));
        }
        return MenuItems.item(material, MenuItems.HEADER + name, lore);
    }

    private static void appendBreakdown(List<String> lore, String name, Map<String, Double> values) {
        if (values == null || values.isEmpty()) return;
        lore.add("");
        lore.add(MenuItems.LABEL + name + ":");
        values.forEach((key, value) -> lore.add("  " + metric(key, value == null ? Double.NaN : value, 3, "")));
    }

    private static String metric(String name, double value, int decimals, String unit) {
        return Double.isNaN(value)
                ? MenuItems.LABEL + name + ": " + MenuItems.MUTED + "INSUFFICIENT DATA"
                : MenuItems.LABEL + name + ": " + MenuItems.VALUE + AdminStyle.number(value, decimals) + unit;
    }

    private void drawComparison(Inventory inventory, TrainingServiceClient training) {
        ModelComparison comparison = ModelComparison.of(training.currentEvaluation(),
                training.candidateEvaluation());
        if (!comparison.present() || comparison.unmeasured()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_DYE, MenuItems.MUTED + "UNMEASURABLE",
                    MenuItems.wrap(MenuItems.MUTED,
                            "There is no pair of evaluations to compare. Import an evaluation for the "
                                    + "current model and for the candidate; no number is invented in "
                                    + "their place.", 44)));
            return;
        }
        int slot = 9;
        for (ModelComparison.Row row : comparison.rows()) {
            if (slot >= 36) break;
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line("Current", Double.isNaN(row.current())
                    ? "INSUFFICIENT DATA" : AdminStyle.number(row.current(), 3) + row.unit()));
            lore.add(MenuItems.line("Candidate", Double.isNaN(row.candidate())
                    ? "INSUFFICIENT DATA" : AdminStyle.number(row.candidate(), 3) + row.unit()));
            if (row.measured()) {
                lore.add(MenuItems.line("Change", (row.delta() > 0 ? "+" : "")
                                + AdminStyle.number(row.delta(), 3) + row.unit(),
                        row.regression() ? MenuItems.BAD : row.improvement() ? MenuItems.GOOD : MenuItems.VALUE));
                lore.add(MenuItems.note(row.higherIsBetter() ? "Higher is better." : "Lower is better."));
                if (row.regression()) lore.add(MenuItems.BAD + "REGRESSION");
            } else {
                lore.add(MenuItems.note("Not measured on both sides; no comparison is made."));
            }
            inventory.setItem(slot++, MenuItems.item(
                    !row.measured() ? Material.GRAY_STAINED_GLASS_PANE
                            : row.regression() ? Material.RED_STAINED_GLASS_PANE
                            : row.improvement() ? Material.LIME_STAINED_GLASS_PANE
                            : Material.YELLOW_STAINED_GLASS_PANE,
                    (row.regression() ? MenuItems.BAD : MenuItems.LABEL) + row.metric(), lore));
        }
        inventory.setItem(40, MenuItems.item(
                comparison.anyRegression() ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                comparison.anyRegression() ? MenuItems.BAD + "REGRESSIONS PRESENT"
                        : MenuItems.GOOD + "NO REGRESSIONS",
                MenuItems.line("Current", AdminStyle.blankIfEmpty(comparison.currentVersion(), "-")),
                MenuItems.line("Candidate", AdminStyle.blankIfEmpty(comparison.candidateVersion(), "-")),
                "",
                MenuItems.note("A better candidate is still only a candidate."),
                MenuItems.note("Nothing here promotes a model.")));
    }

    @Override public void click(InventoryClickEvent event) {
        int slot = event.getSlot();
        if (isFooterClose(slot, rows())) {
            viewer.closeInventory();
            return;
        }
        if (isFooterBack(slot, rows())) {
            back();
            return;
        }
        if (!comparison && slot == SLOT_COMPARE) {
            gui.show(new ModelMenu(gui, viewer, true));
        } else if (!comparison && slot == 33) {
            service().training().poll();
            viewer.sendMessage(MenuItems.MUTED + "Asked the training service for a fresh snapshot.");
            redraw();
        }
    }

    private void back() {
        if (comparison) gui.show(new ModelMenu(gui, viewer, false));
        else gui.show(new TrainingMenu(gui, viewer));
    }
}
