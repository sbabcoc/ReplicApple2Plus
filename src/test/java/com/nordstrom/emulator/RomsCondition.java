package com.nordstrom.emulator;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.lang.reflect.AnnotatedElement;
import java.util.Optional;

/** Skips what {@link RequiresRoms} marks when one of its ROMs isn't supplied. */
final class RomsCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<AnnotatedElement> element = context.getElement();
        Optional<RequiresRoms> required = element.flatMap(e -> AnnotationSupport.findAnnotation(e, RequiresRoms.class));
        if (required.isEmpty()) {
            return ConditionEvaluationResult.enabled("needs no ROMs");
        }
        for (RequiresRoms.Rom rom : required.get().value()) {
            String missing = rom.missing();
            if (missing != null) {
                return ConditionEvaluationResult.disabled(
                    "ROM not supplied: src/main/resources" + missing + " (see README.md)");
            }
        }
        return ConditionEvaluationResult.enabled("ROMs present");
    }
}
