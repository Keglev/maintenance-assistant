package com.keglevich.maintenanceassistant.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that calls the real, paid LLM provider.
 *
 * <p>Two independent guards, because each covers a case the other cannot. The {@code llm-live} tag
 * keeps the class out of every normal build: failsafe excludes it by default and only the
 * {@code llm-live} Maven profile selects it, so a pull request never runs it and never logs it as
 * skipped. The environment condition still skips it when {@code LLM_API_KEY} is absent, so a local
 * run of the profile without a key spends nothing.
 *
 * <p>How to run these tests, locally or through the {@code backend-llm-live-it} workflow, is in
 * {@code docs/live-provider-tests.md}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag(LiveProviderTest.TAG)
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+",
        disabledReason = "needs a real provider key; set LLM_API_KEY to run")
public @interface LiveProviderTest {

    /** The JUnit tag failsafe filters on; must match the llm-live entries in backend/pom.xml. */
    String TAG = "llm-live";
}
