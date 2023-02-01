package com.codewars.junit5;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.lang.Throwable;
import java.util.Optional;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

// https://github.com/junit-team/junit5/blob/master/junit-platform-console/src/main/java/org/junit/platform/console/tasks/XmlReportData.java
public class CodewarsListener implements TestExecutionListener {
  private int failures;
  private int testCount;
  private final Map<TestIdentifier, Instant> startInstants = new ConcurrentHashMap<>();
  private final Map<TestIdentifier, Instant> endInstants = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<TestIdentifier, NavigableSet<String>> reportEntries = new ConcurrentHashMap<>();
  private final Clock clock;

  CodewarsListener() {
    failures = 0;
    testCount = 0;
    clock = Clock.systemDefaultZone();
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    // System.out.printf("\nTestPlan Execution Started: %s\n", testPlan);
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    // System.out.printf("\nTestPlan Execution Finished: %s\n", testPlan);
  }

  @Override
  public void dynamicTestRegistered(TestIdentifier testIdentifier) {
    // System.out.printf("\nDynamic Test Registered: %s - %s\n",
    // testIdentifier.getDisplayName(), testIdentifier.getUniqueId());
  }

  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    // Skip root identifer, e.g., [engine:junit-jupiter]
    if (!testIdentifier.getParentId().isPresent()) {
      return;
    }
    markStarted(testIdentifier);
    if (testIdentifier.isContainer()) {
      System.out.printf("\n<DESCRIBE::>%s\n", testIdentifier.getDisplayName());
    } else if (testIdentifier.isTest()) {
      ++testCount;
      System.out.printf("\n<IT::>%s\n", testIdentifier.getDisplayName());
    }
  }

  // TODO consider adding `<SKIPPED::>` and display properly
  @Override
  public void executionSkipped(TestIdentifier testIdentifier, String reason) {
    if (testIdentifier.isContainer()) {
      System.out.printf("\n<DESCRIBE::>[SKIPPED] %s\n", testIdentifier.getDisplayName());
      if (reason != null && reason != "") {
        System.out.printf("\n<LOG::Skipped Reason>%s\n", reason);
      }
      System.out.println("\n<COMPLETEDIN::>");
    } else if (testIdentifier.isTest()) {
      System.out.printf("\n<IT::>[SKIPPED] %s\n", testIdentifier.getDisplayName());
      if (reason != null && reason != "") {
        System.out.printf("\n<LOG::Skipped Reason>%s\n", reason);
      }
      System.out.println("\n<COMPLETEDIN::>");
    }
  }

  @Override
  public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
    // Skip root identifer, e.g., [engine:junit-jupiter]
    if (!testIdentifier.getParentId().isPresent()) {
      return;
    }
    markFinished(testIdentifier);
    outputReportEntries(testIdentifier);

    switch (testExecutionResult.getStatus()) {
      case SUCCESSFUL:
        if (testIdentifier.isTest()) {
          System.out.println("\n<PASSED::>Test Passed");
        }
        System.out.printf("\n<COMPLETEDIN::>%d\n", getDuration(testIdentifier));
        break;

      case ABORTED: // assumptions not met
        if (testIdentifier.isTest()) {
          ++failures;
          Optional<Throwable> th = testExecutionResult.getThrowable();
          if (th.isPresent()) {
            outputFailure("Aborted", th.get());
          } else {
            System.out.println("\n<FAILED::>Aborted for unknown cause");
          }
        }
        System.out.printf("\n<COMPLETEDIN::>%d\n", getDuration(testIdentifier));
        break;

      case FAILED:
        if (testIdentifier.isTest()) {
          ++failures;
          Optional<Throwable> th = testExecutionResult.getThrowable();
          if (th.isPresent()) {
            outputFailure("Failed", th.get());
          } else {
            System.out.println("\n<FAILED::>Failed for unknown cause");
          }
        } else {
          ++failures;
          Optional<Throwable> th = testExecutionResult.getThrowable();
          if (th.isPresent()) {
            outputError("Crashed", th.get());
          } else {
            System.out.println("\n<ERROR::>Unexpected error occurred");
          }
        }
        System.out.printf("\n<COMPLETEDIN::>%d\n", getDuration(testIdentifier));
        break;

      default:
        throw new Error("Unsupported execution status:" + testExecutionResult.getStatus());
    }
  }

  // > In JUnit Jupiter you should use `TestReporter` where you used to print
  // information to `stdout` or `stderr` in JUnit 4.
  // ```java
  // @Test
  // void reportSingleValue(TestReporter testReporter) {
  // testReporter.publishEntry("a key", "a value");
  // }
  // ```
  public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
    reportEntries(testIdentifier).add(reportEntryToString(entry));
  }

  public int failures() {
    return failures;
  }

  public int testCount() {
    return testCount;
  }

  private static void outputFailure(String kind, Throwable throwable) {
    String msg = throwable.getMessage();
    if (msg == null) {
      System.out.printf("\n<FAILED::>Test %s\n", kind);
    } else {
      System.out.printf("\n<FAILED::>%s\n", formatMessage(msg));
    }
    System.out.printf("\n<LOG:ESC:-Stack Trace>%s\n", formatMessage(readStackTrace(throwable)));
  }

  private static void outputError(String kind, Throwable throwable) {
    String msg = throwable.getMessage();
    if (msg == null) {
      System.out.printf("\n<ERROR::>Test %s\n", kind);
      System.out.printf("\n<LOG:ESC:-Stack Trace>%s\n", formatMessage(readStackTrace(throwable)));
    } else {
      String formattedMessage = formatMessage(msg);
      String formattedStackTrace = formatMessage(readStackTrace(throwable));
      System.out.printf("\n<ERROR::>%s\n", formattedMessage);
      System.out.printf("\n<LOG:ESC:Stack Trace>%s\n", formattedStackTrace);
    }
  }

  // Read the stacktrace of the supplied {@link Throwable} into a String.
  // https://github.com/junit-team/junit5/blob/946c5980074f466de0688297a6d661d32679599a/junit-platform-commons/src/main/java/org/junit/platform/commons/util/ExceptionUtils.java#L76
  private static String readStackTrace(Throwable throwable) {
    StringWriter sw = new StringWriter();
    try (PrintWriter pw = new PrintWriter(sw)) {
      throwable.printStackTrace(pw);
    }
    return sw.toString();
  }

  private void markStarted(TestIdentifier testIdentifier) {
    this.startInstants.put(testIdentifier, this.clock.instant());
  }

  private void markFinished(TestIdentifier testIdentifier) {
    this.endInstants.put(testIdentifier, this.clock.instant());
  }

  private long getDuration(TestIdentifier testIdentifier) {
    Instant start = this.startInstants.getOrDefault(testIdentifier, Instant.EPOCH);
    Instant end = this.endInstants.getOrDefault(testIdentifier, start);
    return Duration.between(start, end).toMillis();
  }

  private static String formatMessage(final String s) {
    return (s == null) ? "" : s.replaceAll(System.lineSeparator(), "<:LF:>");
  }

  private NavigableSet<String> reportEntries(TestIdentifier testIdentifier) {
    return this.reportEntries.computeIfAbsent(testIdentifier, k -> new ConcurrentSkipListSet<String>());
  }

  private void outputReportEntries(TestIdentifier testIdentifier) {
    NavigableSet<String> entries = reportEntries(testIdentifier);
    if (entries.isEmpty())
      return;

    String reports = entries.stream().collect(Collectors.joining("\n\n"));
    System.out.printf("\n<LOG::-Reports>%s\n", formatMessage(reports));
  }

  private String reportEntryToString(ReportEntry entry) {
    return entry
        .getKeyValuePairs()
        .entrySet()
        .stream()
        .map(e -> e.getKey() + " = " + e.getValue())
        .collect(Collectors.joining("\n"));
  }
}
