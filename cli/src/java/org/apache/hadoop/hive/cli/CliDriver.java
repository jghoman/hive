/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jline.ArgumentCompletor;
import jline.Completor;
import jline.ConsoleReader;
import jline.History;
import jline.SimpleCompletor;
import jline.ArgumentCompletor.AbstractArgumentDelimiter;
import jline.ArgumentCompletor.ArgumentDelimiter;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.HiveInterruptUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.HadoopJobExecHelper;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.Utilities.StreamPrinter;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.hive.service.HiveClient;
import org.apache.hadoop.hive.service.HiveServerException;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.thrift.TException;

import sun.misc.Signal;
import sun.misc.SignalHandler;


/**
 * CliDriver.
 *
 */
public class CliDriver {

  public static String prompt = "hive";
  public static String prompt2 = "    "; // when ';' is not yet seen
  public static final int LINES_TO_FETCH = 40; // number of lines to fetch in batch from remote hive server

  public static final String HIVERCFILE = ".hiverc";

  private final LogHelper console;
  private final Configuration conf;

  public CliDriver() {
    SessionState ss = SessionState.get();
    conf = (ss != null) ? ss.getConf() : new Configuration();
    Log LOG = LogFactory.getLog("CliDriver");
    console = new LogHelper(LOG);
  }

  public int processCmd(String cmd) {
    CliSessionState ss = (CliSessionState) SessionState.get();

    String cmd_trimmed = cmd.trim();
    String[] tokens = cmd_trimmed.split("\\s+");
    String cmd_1 = cmd_trimmed.substring(tokens[0].length()).trim();
    int ret = 0;

    if (cmd_trimmed.toLowerCase().equals("quit") || cmd_trimmed.toLowerCase().equals("exit")) {

      // if we have come this far - either the previous commands
      // are all successful or this is command line. in either case
      // this counts as a successful run
      ss.close();
      System.exit(0);

    } else if (tokens[0].equalsIgnoreCase("source")) {
      File sourceFile = new File(cmd_1);
      if (! sourceFile.isFile()){
        console.printError("File: "+ cmd_1 + " is not a file.");
        ret = 1;
      } else {
        try {
          this.processFile(cmd_1);
        } catch (IOException e) {
          console.printError("Failed processing file "+ cmd_1 +" "+ e.getLocalizedMessage(),
            org.apache.hadoop.util.StringUtils.stringifyException(e));
          ret = 1;
        }
      }
    } else if (cmd_trimmed.startsWith("!")) {

      String shell_cmd = cmd_trimmed.substring(1);

      // shell_cmd = "/bin/bash -c \'" + shell_cmd + "\'";
      try {
        Process executor = Runtime.getRuntime().exec(shell_cmd);
        StreamPrinter outPrinter = new StreamPrinter(executor.getInputStream(), null, ss.out);
        StreamPrinter errPrinter = new StreamPrinter(executor.getErrorStream(), null, ss.err);

        outPrinter.start();
        errPrinter.start();

        ret = executor.waitFor();
        if (ret != 0) {
          console.printError("Command failed with exit code = " + ret);
        }
      } catch (Exception e) {
        console.printError("Exception raised from Shell command " + e.getLocalizedMessage(),
            org.apache.hadoop.util.StringUtils.stringifyException(e));
        ret = 1;
      }

    } else if (tokens[0].toLowerCase().equals("list")) {

      SessionState.ResourceType t;
      if (tokens.length < 2 || (t = SessionState.find_resource_type(tokens[1])) == null) {
        console.printError("Usage: list ["
            + StringUtils.join(SessionState.ResourceType.values(), "|") + "] [<value> [<value>]*]");
        ret = 1;
      } else {
        List<String> filter = null;
        if (tokens.length >= 3) {
          System.arraycopy(tokens, 2, tokens, 0, tokens.length - 2);
          filter = Arrays.asList(tokens);
        }
        Set<String> s = ss.list_resource(t, filter);
        if (s != null && !s.isEmpty()) {
          ss.out.println(StringUtils.join(s, "\n"));
        }
      }
    } else if (ss.isRemoteMode()) { // remote mode -- connecting to remote hive server
      HiveClient client = ss.getClient();
      PrintStream out = ss.out;
      PrintStream err = ss.err;

      try {
        client.execute(cmd_trimmed);
        List<String> results;
        do {
          results = client.fetchN(LINES_TO_FETCH);
          for (String line : results) {
            out.println(line);
          }
        } while (results.size() == LINES_TO_FETCH);
      } catch (HiveServerException e) {
        ret = e.getErrorCode();
        if (ret != 0) { // OK if ret == 0 -- reached the EOF
          String errMsg = e.getMessage();
          if (errMsg == null) {
            errMsg = e.toString();
          }
          ret = e.getErrorCode();
          err.println("[Hive Error]: " + errMsg);
        }
      } catch (TException e) {
        String errMsg = e.getMessage();
        if (errMsg == null) {
          errMsg = e.toString();
        }
        ret = -10002;
        err.println("[Thrift Error]: " + errMsg);
      } finally {
        try {
          client.clean();
        } catch (TException e) {
          String errMsg = e.getMessage();
          if (errMsg == null) {
            errMsg = e.toString();
          }
          err.println("[Thrift Error]: Hive server is not cleaned due to thrift exception: "
              + errMsg);
        }
      }
    } else { // local mode
      CommandProcessor proc = CommandProcessorFactory.get(tokens[0], (HiveConf)conf);
      int tryCount = 0;
      boolean needRetry;

      do {
        try {
          needRetry = false;
          if (proc != null) {
            if (proc instanceof Driver) {
              Driver qp = (Driver) proc;
              PrintStream out = ss.out;
              long start = System.currentTimeMillis();
              if (ss.getIsVerbose()) {
                out.println(cmd);
              }

              qp.setTryCount(tryCount);
              ret = qp.run(cmd).getResponseCode();
              if (ret != 0) {
                qp.close();
                return ret;
              }

              ArrayList<String> res = new ArrayList<String>();

              if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_CLI_PRINT_HEADER)) {
                // Print the column names
                boolean first_col = true;
                Schema sc = qp.getSchema();
                for (FieldSchema fs : sc.getFieldSchemas()) {
                  if (!first_col) {
                    out.print('\t');
                  }
                  out.print(fs.getName());
                  first_col = false;
                }
                out.println();
              }

              try {
                while (qp.getResults(res)) {
                  for (String r : res) {
                    out.println(r);
                  }
                  res.clear();
                  if (out.checkError()) {
                    break;
                  }
                }
              } catch (IOException e) {
                console.printError("Failed with exception " + e.getClass().getName() + ":"
                    + e.getMessage(), "\n"
                    + org.apache.hadoop.util.StringUtils.stringifyException(e));
                ret = 1;
              }

              int cret = qp.close();
              if (ret == 0) {
                ret = cret;
              }

              long end = System.currentTimeMillis();
              if (end > start) {
                double timeTaken = (end - start) / 1000.0;
                console.printInfo("Time taken: " + timeTaken + " seconds", null);
              }

            } else {
              if (ss.getIsVerbose()) {
                ss.out.println(tokens[0] + " " + cmd_1);
              }
              ret = proc.run(cmd_1).getResponseCode();
            }
          }
        } catch (CommandNeedRetryException e) {
          console.printInfo("Retry query with a different approach...");
          tryCount++;
          needRetry = true;
        }
      } while (needRetry);
    }

    return ret;
  }

  public int processLine(String line) {
    return processLine(line, false);
  }

  /**
   * Processes a line of semicolon separated commands
   *
   * @param line
   *          The commands to process
   * @param allowInterupting
   *          When true the function will handle SIG_INT (Ctrl+C) by interrupting the processing and
   *          returning -1
   * @return
   */
  public int processLine(String line, boolean allowInterupting) {
    SignalHandler oldSignal = null;
    Signal interupSignal = null;

    if (allowInterupting) {
      // Remember all threads that were running at the time we started line processing.
      // Hook up the custom Ctrl+C handler while processing this line
      interupSignal = new Signal("INT");
      oldSignal = Signal.handle(interupSignal, new SignalHandler() {
        private final Thread cliThread = Thread.currentThread();
        private boolean interruptRequested;

        @Override
        public void handle(Signal signal) {
          boolean initialRequest = !interruptRequested;
          interruptRequested = true;

          // Kill the VM on second ctrl+c
          if (!initialRequest) {
            console.printInfo("Exiting the JVM");
            System.exit(127);
          }

          // Interrupt the CLI thread to stop the current statement and return
          // to prompt
          console.printInfo("Interrupting... Be patient, this might take some time.");
          console.printInfo("Press Ctrl+C again to kill JVM");

          // First, kill any running MR jobs
          HadoopJobExecHelper.killRunningJobs();
          HiveInterruptUtils.interrupt();
          this.cliThread.interrupt();
        }
      });
    }

    try {
      int lastRet = 0, ret = 0;

      String command = "";
      for (String oneCmd : line.split(";")) {

        if (StringUtils.endsWith(oneCmd, "\\")) {
          command += StringUtils.chop(oneCmd) + ";";
          continue;
        } else {
          command += oneCmd;
        }
        if (StringUtils.isBlank(command)) {
          continue;
        }

        ret = processCmd(command);
        command = "";
        lastRet = ret;
        boolean ignoreErrors = HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLIIGNOREERRORS);
        if (ret != 0 && !ignoreErrors) {
          CommandProcessorFactory.clean((HiveConf) conf);
          return ret;
        }
      }
      CommandProcessorFactory.clean((HiveConf) conf);
      return lastRet;
    } finally {
      // Once we are done processing the line, restore the old handler
      if (oldSignal != null && interupSignal != null) {
        Signal.handle(interupSignal, oldSignal);
      }
    }
  }

  public int processReader(BufferedReader r) throws IOException {
    String line;
    StringBuilder qsb = new StringBuilder();

    while ((line = r.readLine()) != null) {
      qsb.append(line + "\n");
    }

    return (processLine(qsb.toString()));
  }

  public int processFile(String fileName) throws IOException {
    FileReader fileReader = null;
    try {
      fileReader = new FileReader(fileName);
      return processReader(new BufferedReader(fileReader));
    } finally {
      if (fileReader != null) {
        fileReader.close();
      }
    }
  }

  public void processInitFiles(CliSessionState ss) throws IOException {
    boolean saveSilent = ss.getIsSilent();
    ss.setIsSilent(true);
    for (String initFile : ss.initFiles) {
      int rc = processFile(initFile);
      if (rc != 0) {
        System.exit(rc);
      }
    }
    if (ss.initFiles.size() == 0) {
      if (System.getenv("HIVE_HOME") != null) {
        String hivercDefault = System.getenv("HIVE_HOME") + File.separator + "bin" + File.separator + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          int rc = processFile(hivercDefault);
          if (rc != 0) {
            System.exit(rc);
          }
        }
      }
      if (System.getProperty("user.home") != null) {
        String hivercUser = System.getProperty("user.home") + File.separator + HIVERCFILE;
        if (new File(hivercUser).exists()) {
          int rc = processFile(hivercUser);
          if (rc != 0) {
            System.exit(rc);
          }
        }
      }
    }
    ss.setIsSilent(saveSilent);
  }

  public static Completor getCommandCompletor () {
    // SimpleCompletor matches against a pre-defined wordlist
    // We start with an empty wordlist and build it up
    SimpleCompletor sc = new SimpleCompletor(new String[0]);

    // We add Hive function names
    // For functions that aren't infix operators, we add an open
    // parenthesis at the end.
    for (String s : FunctionRegistry.getFunctionNames()) {
      if (s.matches("[a-z_]+")) {
        sc.addCandidateString(s + "(");
      } else {
        sc.addCandidateString(s);
      }
    }

    // We add Hive keywords, including lower-cased versions
    for (String s : ParseDriver.getKeywords()) {
      sc.addCandidateString(s);
      sc.addCandidateString(s.toLowerCase());
    }

    // Because we use parentheses in addition to whitespace
    // as a keyword delimiter, we need to define a new ArgumentDelimiter
    // that recognizes parenthesis as a delimiter.
    ArgumentDelimiter delim = new AbstractArgumentDelimiter () {
      @Override
      public boolean isDelimiterChar (String buffer, int pos) {
        char c = buffer.charAt(pos);
        return (Character.isWhitespace(c) || c == '(' || c == ')' ||
          c == '[' || c == ']');
      }
    };

    // The ArgumentCompletor allows us to match multiple tokens
    // in the same line.
    final ArgumentCompletor ac = new ArgumentCompletor(sc, delim);
    // By default ArgumentCompletor is in "strict" mode meaning
    // a token is only auto-completed if all prior tokens
    // match. We don't want that since there are valid tokens
    // that are not in our wordlist (eg. table and column names)
    ac.setStrict(false);

    // ArgumentCompletor always adds a space after a matched token.
    // This is undesirable for function names because a space after
    // the opening parenthesis is unnecessary (and uncommon) in Hive.
    // We stack a custom Completor on top of our ArgumentCompletor
    // to reverse this.
    Completor completor = new Completor () {
      public int complete (String buffer, int offset, List completions) {
        List<String> comp = (List<String>) completions;
        int ret = ac.complete(buffer, offset, completions);
        // ConsoleReader will do the substitution if and only if there
        // is exactly one valid completion, so we ignore other cases.
        if (completions.size() == 1) {
          if (comp.get(0).endsWith("( ")) {
            comp.set(0, comp.get(0).trim());
          }
        }
        return ret;
      }
    };

    return completor;
  }

  public static void main(String[] args) throws Exception {

    OptionsProcessor oproc = new OptionsProcessor();
    if (!oproc.process_stage1(args)) {
      System.exit(1);
    }

    // NOTE: It is critical to do this here so that log4j is reinitialized
    // before any of the other core hive classes are loaded
    SessionState.initHiveLog4j();

    CliSessionState ss = new CliSessionState(new HiveConf(SessionState.class));
    ss.in = System.in;
    try {
      ss.out = new PrintStream(System.out, true, "UTF-8");
      ss.err = new PrintStream(System.err, true, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      System.exit(3);
    }

    if (!oproc.process_stage2(ss)) {
      System.exit(2);
    }
    ss.printInitInfo();

    // set all properties specified via command line
    HiveConf conf = ss.getConf();
    for (Map.Entry<Object, Object> item : ss.cmdProperties.entrySet()) {
      conf.set((String) item.getKey(), (String) item.getValue());
    }

    SessionState.start(ss);

    // connect to Hive Server
    if (ss.getHost() != null) {
      ss.connect();
      if (ss.isRemoteMode()) {
        prompt = "[" + ss.host + ':' + ss.port + "] " + prompt;
        char[] spaces = new char[prompt.length()];
        Arrays.fill(spaces, ' ');
        prompt2 = new String(spaces);
      }
    }

    // CLI remote mode is a thin client: only load auxJars in local mode
    if (!ss.isRemoteMode() && !ShimLoader.getHadoopShims().usesJobShell()) {
      // hadoop-20 and above - we need to augment classpath using hiveconf
      // components
      // see also: code in ExecDriver.java
      ClassLoader loader = conf.getClassLoader();
      String auxJars = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEAUXJARS);
      if (StringUtils.isNotBlank(auxJars)) {
        loader = Utilities.addToClassPath(loader, StringUtils.split(auxJars, ","));
      }
      conf.setClassLoader(loader);
      Thread.currentThread().setContextClassLoader(loader);
    }

    CliDriver cli = new CliDriver();

    // Execute -i init files (always in silent mode)
    cli.processInitFiles(ss);

    if (ss.execString != null) {
      System.exit(cli.processLine(ss.execString));
    }

    try {
      if (ss.fileName != null) {
        System.exit(cli.processFile(ss.fileName));
      }
    } catch (FileNotFoundException e) {
      System.err.println("Could not open input file for reading. (" + e.getMessage() + ")");
      System.exit(3);
    }

    ConsoleReader reader = new ConsoleReader();
    reader.setBellEnabled(false);
    // reader.setDebug(new PrintWriter(new FileWriter("writer.debug", true)));
    reader.addCompletor(getCommandCompletor());

    String line;
    final String HISTORYFILE = ".hivehistory";
    String historyFile = System.getProperty("user.home") + File.separator + HISTORYFILE;
    reader.setHistory(new History(new File(historyFile)));
    int ret = 0;

    String prefix = "";
    String curPrompt = prompt;
    while ((line = reader.readLine(curPrompt + "> ")) != null) {
      if (!prefix.equals("")) {
        prefix += '\n';
      }
      if (line.trim().endsWith(";") && !line.trim().endsWith("\\;")) {
        line = prefix + line;
        ret = cli.processLine(line, true);
        prefix = "";
        curPrompt = prompt;
      } else {
        prefix = prefix + line;
        curPrompt = prompt2;
        continue;
      }
    }

    ss.close();

    System.exit(ret);
  }

}
