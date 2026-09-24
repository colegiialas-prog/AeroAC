package dev.aeroac.platform.bukkit.admin.training;

import dev.aeroac.neural.admin.AdminLabels;
import dev.aeroac.locale.AeroMessages;

import dev.aeroac.neural.admin.AdminPermissions;

import dev.aeroac.neural.admin.AdminStyle;
import dev.aeroac.neural.admin.training.EvaluationSummary;
import dev.aeroac.neural.admin.training.ModelComparison;
import dev.aeroac.neural.admin.training.TrainingJob;
import dev.aeroac.neural.admin.training.TrainingServiceClient;
import dev.aeroac.neural.admin.training.TrainingStatus;
import dev.aeroac.neural.inference.FeatureEncoder;
import dev.aeroac.platform.bukkit.admin.AeroMenu;
import dev.aeroac.platform.bukkit.admin.BukkitAdminGui;
import dev.aeroac.platform.bukkit.admin.MenuItems;
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
        return MenuItems.HEADER + "AERO › " + (comparison ? AeroMessages.tr("gui.training.model_comparison") : AeroMessages.tr("gui.training.model_training"));
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
        footer(inventory, this::back, comparison ? AeroMessages.tr("gui.training.model_training") : AeroMessages.tr("gui.training.the_training_centre"));
    }

    private void drawJob(Inventory inventory, TrainingServiceClient training) {
        TrainingJob job = training.job();
        List<String> lore = new ArrayList<>();
        // The wording and the tone both come from TrainingStatus, so a stage the backend starts
        // reporting — auditing, preparing, calibrating, exporting — renders as itself instead of
        // needing a new branch here every time the status set grows.
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.status"), TrainingStatus.label(job.status()),
                MenuItems.colourOf(TrainingStatus.tone(job.status()))));
        if (job.configured()) {
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.job"), AdminStyle.blankIfEmpty(job.jobId(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.model"), AdminStyle.blankIfEmpty(job.modelType(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.dataset"), AdminStyle.blankIfEmpty(job.datasetVersion(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.feature_schema"), job.featureSchemaVersion() > 0
                    ? "v" + job.featureSchemaVersion() : AdminStyle.NO_DATA));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.window"), AdminStyle.blankIfEmpty(job.window(), "-")));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.heads"), AdminStyle.blankIfEmpty(job.heads(), "-")));
            lore.add("");
            if (job.totalEpochs() > 0) {
                lore.add(MenuItems.line(AeroMessages.tr("gui.training.epoch"), job.epoch() + " / " + job.totalEpochs()));
            }
            lore.add(MenuItems.LABEL + AeroMessages.tr("gui.training.progress") + MenuItems.VALUE
                    + (Double.isNaN(job.progress()) ? AdminStyle.NO_DATA
                            : Math.round(job.progress() * 100) + "%"));
            if (!Double.isNaN(job.progress())) {
                lore.add(MenuItems.GOOD + AdminStyle.bar(job.progress(), 20));
            }
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.train_loss"), AdminStyle.number(job.trainLoss(), 4)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.validation_loss"), AdminStyle.number(job.validationLoss(), 4)));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.elapsed"), AdminStyle.duration(job.elapsedSeconds())));
        }
        if (job.message() != null) {
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED, job.message(), 44));
        }
        lore.add("");
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.server_feature_schema"), "v" + FeatureEncoder.FEATURE_SCHEMA_VERSION));
        inventory.setItem(SLOT_JOB, MenuItems.item(
                job.configured() ? Material.BEACON : Material.GRAY_STAINED_GLASS_PANE,
                MenuItems.HEADER + AeroMessages.tr("gui.training.training_job"), lore));

        inventory.setItem(SLOT_CURRENT, evaluation(AeroMessages.tr("gui.training.card_current"),
                training.currentEvaluation(), Material.ENDER_EYE));
        inventory.setItem(SLOT_CANDIDATE, evaluation(AeroMessages.tr("gui.training.card_candidate"),
                training.candidateEvaluation(), Material.ENDER_PEARL));

        inventory.setItem(SLOT_COMPARE, MenuItems.item(Material.COMPARATOR,
                MenuItems.HEADER + AeroMessages.tr("gui.training.compare"),
                MenuItems.note(AeroMessages.tr("gui.training.current_against_candidate_one_metric_per_row")),
                MenuItems.note(AeroMessages.tr("gui.training.with_regressions_called_out")),
                MenuItems.note(AeroMessages.tr("gui.training.reports_do_not_identify_the_server_s_active_weights"))));

        inventory.setItem(29, MenuItems.item(Material.BARRIER, MenuItems.HEADER + AeroMessages.tr("gui.training.no_automatic_deploy"),
                MenuItems.wrap(MenuItems.MUTED,
                        AeroMessages.tr("gui.training.finishing_training_changes_nothing_on_this_server_it_does_no")
                                + AeroMessages.tr("gui.training.running_model_enable_mitigation_or_move_a_risk_threshold_the")
                                + AeroMessages.tr("gui.training.furthest_a_model_gets_from_here_is_candidate"), 44)));

        inventory.setItem(33, MenuItems.item(Material.CLOCK, MenuItems.HEADER + AeroMessages.tr("gui.training.refresh"),
                MenuItems.note(AeroMessages.tr("gui.training.asks_the_backend_for_a_new_snapshot")),
                MenuItems.note(AeroMessages.tr("gui.training.returns_immediately_nothing_waits_on_the_network"))));
    }

    private ItemStack evaluation(String name, EvaluationSummary evaluation, Material material) {
        List<String> lore = new ArrayList<>();
        if (!evaluation.present()) {
            lore.add(MenuItems.MUTED + AeroMessages.tr("gui.training.insufficient_data"));
            java.nio.file.Path reports = service().reportDirectory();
            if (reports != null) {
                lore.add("");
                lore.addAll(MenuItems.wrap(MenuItems.MUTED, AeroMessages.tr("gui.model.drop_report_here"), 44));
                lore.add(MenuItems.note(reports.toAbsolutePath().toString()));
            }
            lore.add("");
            lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.training.no_evaluation_has_been_imported_for_this_model_run_the_pytho")
                            + AeroMessages.tr("gui.training.import_its_report_nothing_here_is_measured_on_the_server"), 44));
            return MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.HEADER + name, lore);
        }
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.model"), AdminStyle.blankIfEmpty(evaluation.modelVersion(), "-")));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.dataset"), AdminStyle.blankIfEmpty(evaluation.datasetVersion(), "-")));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.feature_schema"), evaluation.featureSchemaVersion() > 0
                ? "v" + evaluation.featureSchemaVersion() : AdminStyle.NO_DATA));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.population"), AdminLabels.population(evaluation.population())));
        lore.add(MenuItems.line(AeroMessages.tr("gui.training.calibration"), AdminLabels.calibration(evaluation.calibration()),
                evaluation.calibration() == EvaluationSummary.Calibration.CALIBRATED
                        ? MenuItems.GOOD : MenuItems.WARN));
        lore.add("");
        if (evaluation.empty()) {
            lore.add(MenuItems.MUTED + AeroMessages.tr("gui.training.unmeasurable"));
            lore.addAll(MenuItems.wrap(MenuItems.MUTED,
                    AeroMessages.tr("gui.training.the_report_carried_no_usable_metrics_nothing_is_substituted"), 44));
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
                ? MenuItems.LABEL + name + ": " + MenuItems.MUTED + AeroMessages.tr("gui.training.insufficient_data")
                : MenuItems.LABEL + name + ": " + MenuItems.VALUE + AdminStyle.number(value, decimals) + unit;
    }

    private void drawComparison(Inventory inventory, TrainingServiceClient training) {
        ModelComparison comparison = ModelComparison.of(training.currentEvaluation(),
                training.candidateEvaluation());
        if (!comparison.present() || comparison.unmeasured()) {
            inventory.setItem(22, MenuItems.item(Material.GRAY_STAINED_GLASS_PANE, MenuItems.MUTED + AeroMessages.tr("gui.training.unmeasurable"),
                    MenuItems.wrap(MenuItems.MUTED,
                            AeroMessages.tr("gui.training.there_is_no_pair_of_evaluations_to_compare_import_an_evaluat")
                                    + AeroMessages.tr("gui.training.current_model_and_for_the_candidate_no_number_is_invented_in")
                                    + AeroMessages.tr("gui.training.their_place"), 44)));
            return;
        }
        int slot = 9;
        for (ModelComparison.Row row : comparison.rows()) {
            if (slot >= 36) break;
            List<String> lore = new ArrayList<>();
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.current"), Double.isNaN(row.current())
                    ? AeroMessages.tr("gui.training.insufficient_data") : AdminStyle.number(row.current(), 3) + row.unit()));
            lore.add(MenuItems.line(AeroMessages.tr("gui.training.candidate"), Double.isNaN(row.candidate())
                    ? AeroMessages.tr("gui.training.insufficient_data") : AdminStyle.number(row.candidate(), 3) + row.unit()));
            if (row.measured()) {
                lore.add(MenuItems.line(AeroMessages.tr("gui.training.change"), (row.delta() > 0 ? "+" : "")
                                + AdminStyle.number(row.delta(), 3) + row.unit(),
                        row.regression() ? MenuItems.BAD : row.improvement() ? MenuItems.GOOD : MenuItems.VALUE));
                lore.add(MenuItems.note(row.higherIsBetter() ? AeroMessages.tr("gui.training.higher_is_better") : AeroMessages.tr("gui.training.lower_is_better")));
                if (row.regression()) lore.add(MenuItems.BAD + AeroMessages.tr("gui.training.regression"));
            } else {
                lore.add(MenuItems.note(AeroMessages.tr("gui.training.not_measured_on_both_sides_no_comparison_is_made")));
            }
            inventory.setItem(slot++, MenuItems.item(
                    !row.measured() ? Material.GRAY_STAINED_GLASS_PANE
                            : row.regression() ? Material.RED_STAINED_GLASS_PANE
                            : row.improvement() ? Material.LIME_STAINED_GLASS_PANE
                            : Material.YELLOW_STAINED_GLASS_PANE,
                    (row.regression() ? MenuItems.BAD : MenuItems.LABEL) + AdminLabels.comparisonMetric(row.metric()), lore));
        }
        inventory.setItem(40, MenuItems.item(
                comparison.anyRegression() ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                comparison.anyRegression() ? MenuItems.BAD + AeroMessages.tr("gui.training.regressions_present")
                        : MenuItems.GOOD + AeroMessages.tr("gui.training.no_regressions"),
                MenuItems.line(AeroMessages.tr("gui.training.current"), AdminStyle.blankIfEmpty(comparison.currentVersion(), "-")),
                MenuItems.line(AeroMessages.tr("gui.training.candidate"), AdminStyle.blankIfEmpty(comparison.candidateVersion(), "-")),
                "",
                MenuItems.note(AeroMessages.tr("gui.training.a_better_candidate_is_still_only_a_candidate")),
                MenuItems.note(AeroMessages.tr("gui.training.nothing_here_promotes_a_model"))));
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
            viewer.sendMessage(MenuItems.MUTED + AeroMessages.tr("gui.training.asked_the_training_service_for_a_fresh_snapshot"));
            redraw();
        }
    }

    private void back() {
        if (comparison) gui.show(new ModelMenu(gui, viewer, false));
        else gui.show(new TrainingMenu(gui, viewer));
    }
}
