package com.abada.engine.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.abada.engine.persistence.entity.InsightAgentFactEntity;
import com.abada.engine.persistence.repository.InsightAgentFactRepository;
import com.abada.engine.persistence.repository.InsightExecutionFactRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** E14: override, calibration, confidence-drift and cost signals over agent facts. */
class InsightAnalyzerAgentSignalsTest {
    private static final Instant START = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T01:00:00Z");

    private final InsightAgentFactRepository agentFacts = mock(InsightAgentFactRepository.class);
    private InsightAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        InsightProperties properties = new InsightProperties();
        ReflectionTestUtils.setField(properties, "minReviewSamples", 10);
        ReflectionTestUtils.setField(properties, "overrideRateThreshold", 0.25);
        ReflectionTestUtils.setField(properties, "highConfidence", 85.0);
        ReflectionTestUtils.setField(properties, "confidenceDriftPoints", 10.0);
        ReflectionTestUtils.setField(properties, "costFactor", 1.5);
        analyzer = new InsightAnalyzer(mock(InsightExecutionFactRepository.class), agentFacts, properties);
        when(agentFacts.findByEndedAtGreaterThanEqualAndEndedAtLessThan(any(), any())).thenReturn(List.of());
        when(agentFacts.findByReviewedAtGreaterThanEqualAndReviewedAtLessThan(any(), any())).thenReturn(List.of());
        when(agentFacts.findByProjectIdAndDefinitionKeyAndDefinitionDeploymentIdAndAgentNodeAndEndedAtBefore(
                any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void anOverrideRateAtOrAboveTheThresholdIsReportedAndBelowItIsNot() {
        when(agentFacts.findByReviewedAtGreaterThanEqualAndReviewedAtLessThan(START, END))
                .thenReturn(reviews(12, 3, 70)); // 25%
        assertThat(signals()).containsExactly("OVERRIDE_RATE");

        when(agentFacts.findByReviewedAtGreaterThanEqualAndReviewedAtLessThan(START, END))
                .thenReturn(reviews(12, 2, 70)); // 16.7%
        assertThat(signals()).isEmpty();
    }

    @Test
    void nothingFiresBelowTheMinimumSampleSize() {
        when(agentFacts.findByReviewedAtGreaterThanEqualAndReviewedAtLessThan(START, END))
                .thenReturn(reviews(9, 9, 95));
        assertThat(signals()).isEmpty();
    }

    @Test
    void overridesOfConfidentOutputsAlsoReportACalibrationGap() {
        when(agentFacts.findByReviewedAtGreaterThanEqualAndReviewedAtLessThan(START, END))
                .thenReturn(reviews(12, 6, 92));
        assertThat(signals()).containsExactlyInAnyOrder("OVERRIDE_RATE", "CALIBRATION_GAP");
    }

    @Test
    void confidenceDriftIsMeasuredAgainstFactsBeforeTheWindowOnly() {
        when(agentFacts.findByEndedAtGreaterThanEqualAndEndedAtLessThan(START, END))
                .thenReturn(visits(10, 70, null));
        when(agentFacts.findByProjectIdAndDefinitionKeyAndDefinitionDeploymentIdAndAgentNodeAndEndedAtBefore(
                "default", "p", "d1", "draft", START)).thenReturn(visits(10, 85, null));
        List<InsightAnalyzer.Finding> findings = new ArrayList<>();
        analyzer.analyzeAgents(START, END, findings);
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.signal()).isEqualTo("CONFIDENCE_DRIFT");
            assertThat(finding.observed()).isEqualTo(15.0);
            assertThat(finding.summary()).contains("falling");
        });

        when(agentFacts.findByProjectIdAndDefinitionKeyAndDefinitionDeploymentIdAndAgentNodeAndEndedAtBefore(
                "default", "p", "d1", "draft", START)).thenReturn(visits(10, 75, null)); // only 5 points
        assertThat(signals()).isEmpty();
    }

    @Test
    void costPerCaseUsesPricedVisitsAndCountsTheRest() {
        List<InsightAgentFactEntity> window = new ArrayList<>(visits(10, 80, new BigDecimal("0.0400")));
        window.addAll(unpriced(5));
        when(agentFacts.findByEndedAtGreaterThanEqualAndEndedAtLessThan(START, END)).thenReturn(window);
        when(agentFacts.findByProjectIdAndDefinitionKeyAndDefinitionDeploymentIdAndAgentNodeAndEndedAtBefore(
                "default", "p", "d1", "draft", START)).thenReturn(visits(10, 80, new BigDecimal("0.0200")));
        List<InsightAnalyzer.Finding> findings = new ArrayList<>();
        analyzer.analyzeAgents(START, END, findings);
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.signal()).isEqualTo("COST_PER_CASE");
            assertThat(finding.samples()).isEqualTo(10); // unpriced visits are left out
        });
    }

    private List<String> signals() {
        List<InsightAnalyzer.Finding> findings = new ArrayList<>();
        analyzer.analyzeAgents(START, END, findings);
        return findings.stream().map(InsightAnalyzer.Finding::signal).toList();
    }

    private static List<InsightAgentFactEntity> reviews(int total, int overrides, double confidence) {
        List<InsightAgentFactEntity> facts = new ArrayList<>(visits(total, confidence, null));
        for (int i = 0; i < facts.size(); i++) {
            facts.get(i).setReviewNode("review");
            facts.get(i).setOverridden(i < overrides);
            facts.get(i).setReviewOutcome(i < overrides ? "reject" : "approve");
            facts.get(i).setReviewedAt(START.plusSeconds(60));
        }
        return facts;
    }

    private static List<InsightAgentFactEntity> visits(int count, double confidence, BigDecimal cost) {
        List<InsightAgentFactEntity> facts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            InsightAgentFactEntity fact = new InsightAgentFactEntity();
            fact.setProjectId("default");
            fact.setDefinitionKey("p");
            fact.setDefinitionDeploymentId("d1");
            fact.setAgentNode("draft");
            fact.setVisitId("v" + i + "-" + confidence + cost);
            fact.setConfidence(confidence);
            fact.setCostUsd(cost);
            fact.setEndedAt(START.plusSeconds(i));
            facts.add(fact);
        }
        return facts;
    }

    private static List<InsightAgentFactEntity> unpriced(int count) {
        List<InsightAgentFactEntity> facts = visits(count, 80, new BigDecimal("9.0"));
        facts.forEach(fact -> fact.setCostUnpriced(true));
        return facts;
    }
}
