//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package org.apache.maven.shared.release.phase;

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
import java.util.List;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmTagParameters;
import org.apache.maven.scm.command.tag.TagScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.shared.release.ReleaseExecutionException;
import org.apache.maven.shared.release.ReleaseFailureException;
import org.apache.maven.shared.release.ReleaseResult;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.env.ReleaseEnvironment;
import org.apache.maven.shared.release.scm.ReleaseScmCommandException;
import org.apache.maven.shared.release.scm.ReleaseScmRepositoryException;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;
import org.apache.maven.shared.release.util.ReleaseUtil;

public class ScmTagPhase extends AbstractReleasePhase {
    private ScmRepositoryConfigurator scmRepositoryConfigurator;

    public ScmTagPhase() {
    }

    public ReleaseResult execute(ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects) throws ReleaseExecutionException, ReleaseFailureException {
        ReleaseResult relResult = new ReleaseResult();
        validateConfiguration(releaseDescriptor);
        if (releaseDescriptor.getWaitBeforeTagging() > 0) {
            this.logInfo(relResult, "Waiting for " + releaseDescriptor.getWaitBeforeTagging() + " seconds before tagging the release.");

            try {
                Thread.sleep(1000L * (long)releaseDescriptor.getWaitBeforeTagging());
            } catch (InterruptedException var14) {
                ;
            }
        }

        this.logInfo(relResult, "Tagging release with the label " + releaseDescriptor.getScmReleaseLabel() + "...");
        ReleaseDescriptor basedirAlignedReleaseDescriptor = ReleaseUtil.createBasedirAlignedReleaseDescriptor(releaseDescriptor, reactorProjects);

        ScmRepository repository;
        ScmProvider provider;
        try {
            repository = this.scmRepositoryConfigurator.getConfiguredRepository(basedirAlignedReleaseDescriptor.getScmSourceUrl(), releaseDescriptor, releaseEnvironment.getSettings());
            repository.getProviderRepository().setPushChanges(releaseDescriptor.isPushChanges());
            provider = this.scmRepositoryConfigurator.getRepositoryProvider(repository);
        } catch (ScmRepositoryException var12) {
            throw new ReleaseScmRepositoryException(var12.getMessage(), var12.getValidationMessages());
        } catch (NoSuchScmProviderException var13) {
            throw new ReleaseExecutionException("Unable to configure SCM repository: " + var13.getMessage(), var13);
        }

        TagScmResult result;
        try {
            ScmFileSet fileSet = new ScmFileSet(new File(basedirAlignedReleaseDescriptor.getWorkingDirectory()));
            String tagName = releaseDescriptor.getScmReleaseLabel();
            ScmTagParameters scmTagParameters = new ScmTagParameters(releaseDescriptor.getScmCommentPrefix() + "copy for tag " + tagName);
            scmTagParameters.setRemoteTagging(releaseDescriptor.isRemoteTagging());
            scmTagParameters.setScmRevision(releaseDescriptor.getScmReleasedPomRevision());
            if (this.getLogger().isDebugEnabled()) {
                this.getLogger().debug("ScmTagPhase :: scmTagParameters remotingTag " + releaseDescriptor.isRemoteTagging());
                this.getLogger().debug("ScmTagPhase :: scmTagParameters scmRevision " + releaseDescriptor.getScmReleasedPomRevision());
                this.getLogger().debug("ScmTagPhase :: fileSet  " + fileSet);
            }
            this.getLogger().info("------------ScmTagPhase:"+provider.getClass().getName());
            result = provider.tag(repository, fileSet, tagName, scmTagParameters);
        } catch (ScmException var15) {
            throw new ReleaseExecutionException("An error is occurred in the tag process: " + var15.getMessage(), var15);
        }

        if (!result.isSuccess()) {
            throw new ReleaseScmCommandException("Unable to tag SCM", result);
        } else {
            relResult.setResultCode(0);
            return relResult;
        }
    }

    public ReleaseResult simulate(ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects) throws ReleaseExecutionException, ReleaseFailureException {
        ReleaseResult result = new ReleaseResult();
        validateConfiguration(releaseDescriptor);
        ReleaseDescriptor basedirAlignedReleaseDescriptor = ReleaseUtil.createBasedirAlignedReleaseDescriptor(releaseDescriptor, reactorProjects);
        if (releaseDescriptor.isRemoteTagging()) {
            this.logInfo(result, "Full run would be tagging working copy " + basedirAlignedReleaseDescriptor.getWorkingDirectory() + " with label: '" + releaseDescriptor.getScmReleaseLabel() + "'");
        } else {
            this.logInfo(result, "Full run would be tagging remotely " + basedirAlignedReleaseDescriptor.getScmSourceUrl() + " with label: '" + releaseDescriptor.getScmReleaseLabel() + "'");
        }

        result.setResultCode(0);
        return result;
    }

    private static void validateConfiguration(ReleaseDescriptor releaseDescriptor) throws ReleaseFailureException {
        if (releaseDescriptor.getScmReleaseLabel() == null) {
            throw new ReleaseFailureException(" A release label is required for committing ");
        }
    }
}
