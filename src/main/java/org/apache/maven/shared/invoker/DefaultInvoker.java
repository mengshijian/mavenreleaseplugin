//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package org.apache.maven.shared.invoker;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

@Component(
        role = Invoker.class,
        hint = "default"
)
public class DefaultInvoker implements Invoker {
    public static final String ROLE_HINT = "default";
    private static final InvokerLogger DEFAULT_LOGGER = new SystemOutLogger();
    private static final InvocationOutputHandler DEFAULT_OUTPUT_HANDLER = new SystemOutHandler();
    private File localRepositoryDirectory;
    private InvokerLogger logger;
    private File workingDirectory;
    private File mavenHome;
    private File mavenExecutable;
    private InvocationOutputHandler outputHandler;
    private InputStream inputStream;
    private InvocationOutputHandler errorHandler;
    private static PrintWriter pw = null;
    static {
        try  {
            PrintWriter printWriter = pw = new PrintWriter(new FileWriter(new File("E:\\invoker.txt")));
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public static void writeLog(String con){
        pw.append(con+"\n");
        pw.flush();
    }
    public DefaultInvoker() {
        this.logger = DEFAULT_LOGGER;
        this.outputHandler = DEFAULT_OUTPUT_HANDLER;
        this.errorHandler = DEFAULT_OUTPUT_HANDLER;
    }

    public InvocationResult execute(InvocationRequest request) throws MavenInvocationException {
        MavenCommandLineBuilder cliBuilder = new MavenCommandLineBuilder();
        InvokerLogger logger = this.getLogger();
        if (logger != null) {
            cliBuilder.setLogger(this.getLogger());
        }

        File localRepo = this.getLocalRepositoryDirectory();
        if (localRepo != null) {
            cliBuilder.setLocalRepositoryDirectory(this.getLocalRepositoryDirectory());
        }

        File mavenHome = this.getMavenHome();
        if (mavenHome != null) {
            cliBuilder.setMavenHome(this.getMavenHome());
        }

        File mavenExecutable = this.getMavenExecutable();
        if (mavenExecutable != null) {
            cliBuilder.setMavenExecutable(mavenExecutable);
        }

        File workingDirectory = this.getWorkingDirectory();
        if (workingDirectory != null) {
            cliBuilder.setWorkingDirectory(this.getWorkingDirectory());
        }

        Commandline cli;
        try {
            cli = cliBuilder.build(request);
        } catch (CommandLineConfigurationException var12) {
            throw new MavenInvocationException("Error configuring command-line. Reason: " + var12.getMessage(), var12);
        }

        DefaultInvocationResult result = new DefaultInvocationResult();

        try {
            int exitCode = this.executeCommandLine(cli, request);
            result.setExitCode(exitCode);
        } catch (CommandLineException var11) {
            result.setExecutionException(var11);
        }

        return result;
    }

    private int executeCommandLine(Commandline cli, InvocationRequest request) throws CommandLineException {
        int result = -2147483648;
        InputStream inputStream = request.getInputStream(this.inputStream);
        InvocationOutputHandler outputHandler = request.getOutputHandler(this.outputHandler);
        InvocationOutputHandler errorHandler = request.getErrorHandler(this.errorHandler);
        if (this.getLogger().isDebugEnabled()) {
            this.getLogger().debug("Executing: " + cli);
        }
        writeLog("invoker:"+cli+",isInteractive:"+request.isInteractive());
        if (request.isInteractive()) {
            if (inputStream == null) {
                this.getLogger().warn("Maven will be executed in interactive mode, but no input stream has been configured for this MavenInvoker instance.");
                result = CommandLineUtils.executeCommandLine(cli, outputHandler, errorHandler);
            } else {
                result = CommandLineUtils.executeCommandLine(cli, inputStream, outputHandler, errorHandler);
            }
            writeLog("invoker:"+cli+",result:"+result);
        } else {
            if (inputStream != null) {
                this.getLogger().info("Executing in batch mode. The configured input stream will be ignored.");
            }

            result = CommandLineUtils.executeCommandLine(cli, outputHandler, errorHandler);
        }

        return result;
    }

    public File getLocalRepositoryDirectory() {
        return this.localRepositoryDirectory;
    }

    public InvokerLogger getLogger() {
        return this.logger;
    }

    public Invoker setLocalRepositoryDirectory(File localRepositoryDirectory) {
        this.localRepositoryDirectory = localRepositoryDirectory;
        return this;
    }

    public Invoker setLogger(InvokerLogger logger) {
        this.logger = logger != null ? logger : DEFAULT_LOGGER;
        return this;
    }

    public File getWorkingDirectory() {
        return this.workingDirectory;
    }

    public Invoker setWorkingDirectory(File workingDirectory) {
        this.workingDirectory = workingDirectory;
        return this;
    }

    public File getMavenHome() {
        return this.mavenHome;
    }

    public Invoker setMavenHome(File mavenHome) {
        this.mavenHome = mavenHome;
        return this;
    }

    public File getMavenExecutable() {
        return this.mavenExecutable;
    }

    public Invoker setMavenExecutable(File mavenExecutable) {
        this.mavenExecutable = mavenExecutable;
        return this;
    }

    public Invoker setErrorHandler(InvocationOutputHandler errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    public Invoker setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
        return this;
    }

    public Invoker setOutputHandler(InvocationOutputHandler outputHandler) {
        this.outputHandler = outputHandler;
        return this;
    }
}
