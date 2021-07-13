package io.snyk.jenkins;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.util.ArgumentListBuilder;
import io.snyk.jenkins.command.Command;
import io.snyk.jenkins.command.CommandLine;
import io.snyk.jenkins.config.SnykConfig;
import io.snyk.jenkins.model.ObjectMapperHelper;
import io.snyk.jenkins.model.SnykTestResult;
import io.snyk.jenkins.tools.SnykInstallation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

import static hudson.Util.fixEmptyAndTrim;
import static io.snyk.jenkins.config.SnykConstants.SNYK_TEST_REPORT_JSON;

public class SnykTest {

  private static final Logger LOG = LoggerFactory.getLogger(SnykTest.class);

  public static int testProject(
    SnykContext context,
    SnykConfig config,
    SnykInstallation installation
  ) throws IOException, InterruptedException {
    PrintStream logger = context.getLogger();
    FilePath workspace = context.getWorkspace();
    Launcher launcher = context.getLauncher();
    EnvVars envVars = context.getEnvVars();

    FilePath stdoutPath = workspace.child(SNYK_TEST_REPORT_JSON);

    ArgumentListBuilder command = CommandLine
      .asArgumentList(
        installation.getSnykExecutable(launcher),
        Command.TEST,
        config,
        envVars
      )
      .add("--json");

    int exitCode;
    try (OutputStream stdoutWriter = stdoutPath.write()) {
      logger.println("Testing project...");
      logger.println("> " + command);
      exitCode = launcher.launch()
        .cmds(command)
        .envs(envVars)
        .stdout(stdoutWriter)
        .stderr(logger)
        .quiet(true)
        .pwd(workspace)
        .join();
    }

    String stdout = stdoutPath.readToString();
    if (LOG.isTraceEnabled()) {
      LOG.trace("snyk test command: {}", command);
      LOG.trace("snyk test exit code: {}", exitCode);
      LOG.trace("snyk test stdout: {}", stdout);
    }

    SnykTestResult result = ObjectMapperHelper.unmarshallTestResult(stdout);
    if (result == null) {
      throw new AbortException("Failed to parse test output.");
    }
    if (fixEmptyAndTrim(result.error) != null) {
      throw new AbortException("An error occurred. " + result.error);
    }
    if (exitCode >= 2) {
      throw new AbortException("An error occurred. (Exit Code: " + exitCode + ")");
    }
    if (!result.ok) {
      logger.println("Vulnerabilities found!");
      logger.printf(
        "Result: %s known vulnerabilities | %s dependencies%n",
        result.uniqueCount,
        result.dependencyCount
      );
    }

    return exitCode;
  }
}
