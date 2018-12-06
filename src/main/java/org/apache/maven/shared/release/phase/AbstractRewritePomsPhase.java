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
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.Scm;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.edit.EditScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.shared.release.ReleaseExecutionException;
import org.apache.maven.shared.release.ReleaseFailureException;
import org.apache.maven.shared.release.ReleaseResult;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.env.ReleaseEnvironment;
import org.apache.maven.shared.release.scm.IdentifiedScm;
import org.apache.maven.shared.release.scm.ReleaseScmCommandException;
import org.apache.maven.shared.release.scm.ReleaseScmRepositoryException;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;
import org.apache.maven.shared.release.scm.ScmTranslator;
import org.apache.maven.shared.release.util.ReleaseUtil;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.XmlStreamWriter;
import org.jdom.CDATA;
import org.jdom.Comment;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.Text;
import org.jdom.filter.ContentFilter;
import org.jdom.filter.ElementFilter;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

public abstract class AbstractRewritePomsPhase extends AbstractReleasePhase {
    private static Map<String,String> propertyVersionMap = new HashMap<String,String>();
    private static Map<String,String> originalVersionMap = new HashMap<String,String>();//原始版本
    private static Map<String,String> beforeOriginalVersionMap = new HashMap<String,String>();//变更之前的版本号

    private ScmRepositoryConfigurator scmRepositoryConfigurator;
    private Map<String, ScmTranslator> scmTranslators;
    private String pomSuffix;
    private String ls;

    public AbstractRewritePomsPhase() {
        this.ls = ReleaseUtil.LS;
    }

    protected final Map<String, ScmTranslator> getScmTranslators() {
        return this.scmTranslators;
    }

    public void setLs(String ls) {
        this.ls = ls;
    }

    public ReleaseResult execute(ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects) throws ReleaseExecutionException, ReleaseFailureException {
        ReleaseResult result = new ReleaseResult();
        this.transform(releaseDescriptor, releaseEnvironment, reactorProjects, false, result);
        result.setResultCode(0);
        return result;
    }

    private void transform(ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects, boolean simulate, ReleaseResult result) throws ReleaseExecutionException, ReleaseFailureException {
        Iterator i2$ = reactorProjects.iterator();
        //首先解析所有的pom文件，将含有${}，并且包含有-SNAPSHOT结尾的名称和下一个版本号找到存放在propertyVersionMap中
        while(i2$.hasNext()) {
            MavenProject project = (MavenProject)i2$.next();
            this.transformProjectBefore(project, releaseDescriptor, releaseEnvironment, reactorProjects, simulate, result);
        }

        Iterator i$ = reactorProjects.iterator();

        while(i$.hasNext()) {
            MavenProject project = (MavenProject)i$.next();
            this.logInfo(result, "Transforming '" + project.getName() + "'...");
            this.transformProject(project, releaseDescriptor, releaseEnvironment, reactorProjects, simulate, result);
        }

    }

    private void transformProject(MavenProject project, ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects, boolean simulate, ReleaseResult result) throws ReleaseExecutionException, ReleaseFailureException {
        String intro = null;
        String outtro = null;

        Document document;
        try {
            String content = ReleaseUtil.readXmlFile(ReleaseUtil.getStandardPom(project), this.ls);
            content = content.replaceAll("<([^!][^>]*?)\\s{2,}([^>]*?)>", "<$1 $2>");
            content = content.replaceAll("(\\s{2,}|[^\\s])/>", "$1 />");
            SAXBuilder builder = new SAXBuilder();
            document = builder.build(new StringReader(content));
            this.normaliseLineEndings(document);
            StringWriter w = new StringWriter();
            Format format = Format.getRawFormat();
            format.setLineSeparator(this.ls);
            XMLOutputter out = new XMLOutputter(format);
            out.output(document.getRootElement(), w);
            int index = content.indexOf(w.toString());
            if (index >= 0) {
                intro = content.substring(0, index);
                outtro = content.substring(index + w.toString().length());
            } else {
                String SPACE = "\\s++";
                String XML = "<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>";
                String INTSUB = "\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]";
                String DOCTYPE = "<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>";
                String PI = "<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>";
                String COMMENT = "<!--(?:[^-]|(?:-[^-]))*+-->";
                String INTRO = "(?:(?:\\s++)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>)|(?:<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*";
                String OUTRO = "(?:(?:\\s++)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*";
                String POM = "(?s)((?:(?:\\s++)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>)|(?:<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)(.*?)((?:(?:\\s++)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)";
                Matcher matcher = Pattern.compile("(?s)((?:(?:\\s++)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>)|(?:<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)(.*?)((?:(?:\\s++)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)").matcher(content);
                if (matcher.matches()) {
                    intro = matcher.group(1);
                    outtro = matcher.group(matcher.groupCount());
                }
            }
        } catch (JDOMException var28) {
            throw new ReleaseExecutionException("Error reading POM: " + var28.getMessage(), var28);
        } catch (IOException var29) {
            throw new ReleaseExecutionException("Error reading POM: " + var29.getMessage(), var29);
        }

        ScmRepository scmRepository = null;
        ScmProvider provider = null;
        if (this.isUpdateScm()) {
            try {
                scmRepository = this.scmRepositoryConfigurator.getConfiguredRepository(releaseDescriptor, releaseEnvironment.getSettings());
                provider = this.scmRepositoryConfigurator.getRepositoryProvider(scmRepository);
            } catch (ScmRepositoryException var26) {
                throw new ReleaseScmRepositoryException(var26.getMessage(), var26.getValidationMessages());
            } catch (NoSuchScmProviderException var27) {
                throw new ReleaseExecutionException("Unable to configure SCM repository: " + var27.getMessage(), var27);
            }
        }

        this.transformDocument(project, document.getRootElement(), releaseDescriptor, reactorProjects, scmRepository, result, simulate);
        File pomFile = ReleaseUtil.getStandardPom(project);
        if (simulate) {
            File outputFile = new File(pomFile.getParentFile(), pomFile.getName() + "." + this.pomSuffix);
            this.writePom(outputFile, document, releaseDescriptor, project.getModelVersion(), intro, outtro);
        } else {
            this.writePom(pomFile, document, releaseDescriptor, project.getModelVersion(), intro, outtro, scmRepository, provider);
        }

    }

    private void transformProjectBefore(MavenProject project, ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects, boolean simulate, ReleaseResult result) throws ReleaseExecutionException, ReleaseFailureException {
        String intro = null;
        String outtro = null;

        Document document;
        try {
            String content = ReleaseUtil.readXmlFile(ReleaseUtil.getStandardPom(project), this.ls);
            content = content.replaceAll("<([^!][^>]*?)\\s{2,}([^>]*?)>", "<$1 $2>");
            content = content.replaceAll("(\\s{2,}|[^\\s])/>", "$1 />");
            SAXBuilder builder = new SAXBuilder();
            document = builder.build(new StringReader(content));
            this.normaliseLineEndings(document);
            StringWriter w = new StringWriter();
            Format format = Format.getRawFormat();
            format.setLineSeparator(this.ls);
            XMLOutputter out = new XMLOutputter(format);
            out.output(document.getRootElement(), w);
            int index = content.indexOf(w.toString());
            if (index >= 0) {
                intro = content.substring(0, index);
                outtro = content.substring(index + w.toString().length());
            } else {
                String SPACE = "\\s++";
                String XML = "<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>";
                String INTSUB = "\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]";
                String DOCTYPE = "<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>";
                String PI = "<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>";
                String COMMENT = "<!--(?:[^-]|(?:-[^-]))*+-->";
                String INTRO = "(?:(?:\\s++)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>)|(?:<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*";
                String OUTRO = "(?:(?:\\s++)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*";
                String POM = "(?s)((?:(?:\\s++)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>)|(?:<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)(.*?)((?:(?:\\s++)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)";
                Matcher matcher = Pattern.compile("(?s)((?:(?:\\s++)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>)|(?:<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^']*+')|(?:\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+\\]))*+>)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)(.*?)((?:(?:\\s++)|(?:<!--(?:[^-]|(?:-[^-]))*+-->)|(?:<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^']*+'))*+>))*)").matcher(content);
                if (matcher.matches()) {
                    intro = matcher.group(1);
                    outtro = matcher.group(matcher.groupCount());
                }
            }
        } catch (JDOMException var28) {
            throw new ReleaseExecutionException("Error reading POM: " + var28.getMessage(), var28);
        } catch (IOException var29) {
            throw new ReleaseExecutionException("Error reading POM: " + var29.getMessage(), var29);
        }


        this.transformDocumentBefore(project, document.getRootElement(), releaseDescriptor, reactorProjects, null, result, simulate);
    }

    private void normaliseLineEndings(Document document) {
        Iterator i = document.getDescendants(new ContentFilter(8));

        while(i.hasNext()) {
            Comment c = (Comment)i.next();
            c.setText(ReleaseUtil.normalizeLineEndings(c.getText(), this.ls));
        }

        i = document.getDescendants(new ContentFilter(2));

        while(i.hasNext()) {
            CDATA c = (CDATA)i.next();
            c.setText(ReleaseUtil.normalizeLineEndings(c.getText(), this.ls));
        }

    }

    private void transformDocumentBefore(MavenProject project, Element rootElement, ReleaseDescriptor releaseDescriptor, List<MavenProject> reactorProjects, ScmRepository scmRepository, ReleaseResult result, boolean simulate) throws ReleaseExecutionException, ReleaseFailureException {
        Namespace namespace = rootElement.getNamespace();
        Map<String, String> mappedVersions = this.getNextVersionMap(releaseDescriptor);
        Map<String, String> originalVersions = this.getOriginalVersionMap(releaseDescriptor, reactorProjects, simulate);
        Map<String, Map<String, String>> resolvedSnapshotDependencies = releaseDescriptor.getResolvedSnapshotDependencies();
        Model model = project.getModel();
        Element properties = rootElement.getChild("properties", namespace);

        /**
         * 查找所有的属性值
         */
        if(properties!=null) {
            List<Element> list = properties.getChildren();
            for (int i = 0; list != null && i < list.size(); i++) {
                Element propEle = list.get(i);
                String expression = propEle.getName();
                String originalVersion = propEle.getTextTrim();
                //如果属性值以SNAPSHOT结尾，表示当前属性为版本配置属性，SNAPSHOT版本加1
                if (originalVersion.endsWith("-SNAPSHOT")) {
                    String version = originalVersion.substring(0,
                            originalVersion.lastIndexOf("-"));
                    int nextVersion = Integer.parseInt(
                            version.substring(
                                    version.lastIndexOf(".") + 1));// + 1;
                    String tempVersion = version.substring(0,
                            version.lastIndexOf(".") + 1) + nextVersion
                            + "-SNAPSHOT";
                    propertyVersionMap.put(expression, tempVersion);//将计算后的SNAPSHOT版本属性值放到集合中

                    String tempTagVersion = originalVersion.substring(0,originalVersion.indexOf("-SNAPSHOT"));//去掉SNAPSHOT即为release版本值
                    originalVersionMap.put(expression,tempTagVersion);

                    this.logInfo(result,
                            "The version could not be updated--: " + expression
                                    + ",originalVersion:"
                                    + originalVersion + ",mappedVersion:" + tempVersion);
                }
            }
        }
    }

    private void transformDocument(MavenProject project, Element rootElement, ReleaseDescriptor releaseDescriptor, List<MavenProject> reactorProjects, ScmRepository scmRepository, ReleaseResult result, boolean simulate) throws ReleaseExecutionException, ReleaseFailureException {
        Namespace namespace = rootElement.getNamespace();
        Map<String, String> mappedVersions = this.getNextVersionMap(releaseDescriptor);
        Map<String, String> originalVersions = this.getOriginalVersionMap(releaseDescriptor, reactorProjects, simulate);
        Map<String, Map<String, String>> resolvedSnapshotDependencies = releaseDescriptor.getResolvedSnapshotDependencies();
        Model model = project.getModel();
        Element properties = rootElement.getChild("properties", namespace);
        String parentVersion = this.rewriteParent(project, rootElement, namespace, mappedVersions, resolvedSnapshotDependencies, originalVersions);//修复parent元素的值
        String projectId = ArtifactUtils.versionlessKey(project.getGroupId(), project.getArtifactId());
        this.rewriteVersion(rootElement, namespace, mappedVersions, projectId, project, parentVersion,originalVersions);//修改当前pom文件中version的值
        List<Element> roots = new ArrayList();
        roots.add(rootElement);
        roots.addAll(this.getChildren(rootElement, "profiles", "profile"));

        Iterator<String> keys = this.propertyVersionMap.keySet().iterator();
        while(properties!=null&&keys.hasNext()){
            /**
             * 在开始修改每一个pom文件之前先修改properties中包含有propertyVersionMap集合中key的对象
             */
            String key = keys.next();
            Element property = properties.getChild(key, properties.getNamespace());
            if(property!=null) {
                String mappedVersion = null;
                if("tag".equals(this.pomSuffix)){
                    //pomSuffix等于tag,表示当前正在打tag的版本，所以tag版本号从originalVersionMap集合中获取
                    mappedVersion = originalVersionMap.get(key);
                }else{
                    //否则正在生成下一个版本的版本号
                    mappedVersion = propertyVersionMap.get(key);
                }
                String propertyValue = property.getTextTrim();
                this.rewriteValue(property, mappedVersion);//必须调用该方法来修改
            }
        }

        Iterator i$ = roots.iterator();

        while(i$.hasNext()) {
            Element root = (Element)i$.next();
            this.rewriteArtifactVersions(this.getChildren(root, "dependencies", "dependency"), mappedVersions, resolvedSnapshotDependencies, originalVersions, model, properties, result, releaseDescriptor);
            this.rewriteArtifactVersions(this.getChildren(root, "dependencyManagement", "dependencies", "dependency"), mappedVersions, resolvedSnapshotDependencies, originalVersions, model, properties, result, releaseDescriptor);
            this.rewriteArtifactVersions(this.getChildren(root, "build", "extensions", "extension"), mappedVersions, resolvedSnapshotDependencies, originalVersions, model, properties, result, releaseDescriptor);
            List<Element> pluginElements = new ArrayList();
            pluginElements.addAll(this.getChildren(root, "build", "plugins", "plugin"));
            pluginElements.addAll(this.getChildren(root, "build", "pluginManagement", "plugins", "plugin"));
            this.rewriteArtifactVersions(pluginElements, mappedVersions, resolvedSnapshotDependencies, originalVersions, model, properties, result, releaseDescriptor);
            Iterator i1$ = pluginElements.iterator();

            while(i1$.hasNext()) {
                Element pluginElement = (Element)i1$.next();
                this.rewriteArtifactVersions(this.getChildren(pluginElement, "dependencies", "dependency"), mappedVersions, resolvedSnapshotDependencies, originalVersions, model, properties, result, releaseDescriptor);
            }

            this.rewriteArtifactVersions(this.getChildren(root, "reporting", "plugins", "plugin"), mappedVersions, resolvedSnapshotDependencies, originalVersions, model, properties, result, releaseDescriptor);
        }

        String commonBasedir;
        try {
            commonBasedir = ReleaseUtil.getCommonBasedir(reactorProjects);
        } catch (IOException var22) {
            throw new ReleaseExecutionException("Exception occurred while calculating common basedir: " + var22.getMessage(), var22);
        }

        this.transformScm(project, rootElement, namespace, releaseDescriptor, projectId, scmRepository, result, commonBasedir);
    }

    private List<Element> getChildren(Element root, String... names) {
        Element parent = root;

        for(int i = 0; i < names.length - 1 && parent != null; ++i) {
            parent = parent.getChild(names[i], parent.getNamespace());
        }

        return parent == null ? Collections.emptyList() : parent.getChildren(names[names.length - 1], parent.getNamespace());
    }

    private void rewriteValue(Element element, String value) {
        Text text = null;
        if (element.getContent() != null) {
            Iterator it = element.getContent().iterator();

            label32:
            while(it.hasNext()) {
                Object content = it.next();
                if (content instanceof Text && ((Text)content).getTextTrim().length() > 0) {
                    text = (Text)content;

                    while(true) {
                        if (!it.hasNext()) {
                            break label32;
                        }

                        content = it.next();
                        if (!(content instanceof Text)) {
                            break label32;
                        }

                        text.append((Text)content);
                        it.remove();
                    }
                }
            }
        }

        if (text == null) {
            element.addContent(value);
        } else {
            String chars = text.getText();
            String trimmed = text.getTextTrim();
            int idx = chars.indexOf(trimmed);
            String leadingWhitespace = chars.substring(0, idx);
            String trailingWhitespace = chars.substring(idx + trimmed.length());
            text.setText(leadingWhitespace + value + trailingWhitespace);
        }

    }

    /*private static PrintWriter print;
    static{
        FileWriter fw = null;
        try {
            //如果文件存在，则追加内容；如果文件不存在，则创建文件
            File f=new File("E:\\dd.txt");
            fw = new FileWriter(f, true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        print = new PrintWriter(fw);
    }*/

    private void rewriteVersion(Element rootElement, Namespace namespace, Map<String, String> mappedVersions, String projectId, MavenProject project, String parentVersion,Map<String, String> originalVersions) throws ReleaseFailureException {
        Element versionElement = rootElement.getChild("version", namespace);
        Element packagingElement = rootElement.getChild("packaging",namespace);
        String packaging = packagingElement==null?null:packagingElement.getTextTrim();
        String version = (String)mappedVersions.get(projectId);
        if (version == null) {
            throw new ReleaseFailureException("Version for '" + project.getName() + "' was not mapped");
        } else {
            if (versionElement == null) {
                if (!"next".equals(this.pomSuffix)&&!version.equals(parentVersion)) {
                    Element artifactIdElement = rootElement.getChild("artifactId", namespace);
                    int index = rootElement.indexOf(artifactIdElement);
                    versionElement = new Element("version", namespace);
                    versionElement.setText(version);
                    rootElement.addContent(index + 1, new Text("\n  "));
                    rootElement.addContent(index + 2, versionElement);
                }
            } else {
                String key = ArtifactUtils.versionlessKey(project.getGroupId(), project.getArtifactId());
                /*if("next".equals(this.pomSuffix)&&key.toLowerCase().endsWith("service")&&!"pom".equals(packaging)){
                    //修改原版本不是以SNAPSHOT结尾，则保持版本号不变
                    version = versionElement.getTextTrim();
                }*/
                if("next".equals(this.pomSuffix)){
                    //修改原版本不是以SNAPSHOT结尾，则保持版本号不变
                   /* if(key.toLowerCase().endsWith("service")&&!"pom".equals(packaging)){
                        version = versionElement.getTextTrim();
                    }else if(version.endsWith("SNAPSHOT")){*/
                        version = version.substring(0,
                                version.lastIndexOf("-"));
                        int nextVersion = Integer.parseInt(
                                version.substring(
                                        version.lastIndexOf(".") + 1)) - 1;
                        version = version.substring(0,
                                version.lastIndexOf(".") + 1) + nextVersion
                                + "-SNAPSHOT";
                    //}
                }

                this.rewriteValue(versionElement, version);
            }

        }
    }

    private String rewriteParent(MavenProject project, Element rootElement, Namespace namespace, Map<String, String> mappedVersions, Map<String, Map<String, String>> resolvedSnapshotDependencies, Map<String, String> originalVersions) throws ReleaseFailureException {
        String parentVersion = null;
        if (project.hasParent()) {
            Element parentElement = rootElement.getChild("parent", namespace);
            Element versionElement = parentElement.getChild("version", namespace);
            MavenProject parent = project.getParent();
            String key = ArtifactUtils.versionlessKey(parent.getGroupId(), parent.getArtifactId());
            parentVersion = (String)mappedVersions.get(key);
            if (parentVersion == null) {
                parentVersion = this.getResolvedSnapshotVersion(key, resolvedSnapshotDependencies);
            }

            if (parentVersion == null) {
                if (parent.getVersion().equals(originalVersions.get(key))) {
                    throw new ReleaseFailureException("Version for parent '" + parent.getName() + "' was not mapped");
                }
            } else {
                /*if("next".equals(this.pomSuffix)){
                    //修改原版本不是以SNAPSHOT结尾，则保持版本号不变
                    parentVersion = versionElement.getTextTrim();
                }*/
                if("next".equals(this.pomSuffix)){
                    //修改原版本不是以SNAPSHOT结尾，则保持版本号不变
                    if(parentVersion.endsWith("SNAPSHOT")){
                        parentVersion = parentVersion.substring(0,
                                parentVersion.lastIndexOf("-"));
                        int nextVersion = Integer.parseInt(
                                parentVersion.substring(
                                        parentVersion.lastIndexOf(".") + 1)) - 1;
                        parentVersion = parentVersion.substring(0,
                                parentVersion.lastIndexOf(".") + 1) + nextVersion
                                + "-SNAPSHOT";
                    }
                }
                this.rewriteValue(versionElement, parentVersion);
            }
        }

        return parentVersion;
    }

    private void rewriteArtifactVersions(Collection<Element> elements, Map<String, String> mappedVersions, Map<String, Map<String, String>> resolvedSnapshotDependencies, Map<String, String> originalVersions, Model projectModel, Element properties, ReleaseResult result, ReleaseDescriptor releaseDescriptor) throws ReleaseExecutionException, ReleaseFailureException {
        if (elements != null) {
            String projectId = ArtifactUtils.versionlessKey(projectModel.getGroupId(), projectModel.getArtifactId());
            Iterator i$ = elements.iterator();

            while(true) {
                while(true) {
                    while(true) {
                        Element versionElement;
                        String rawVersion;
                        String artifactId;
                        String key;
                        String resolvedSnapshotVersion;
                        String mappedVersion;
                        String originalVersion;
                        do {
                            String groupId;
                            Element artifactIdElement;
                            do {
                                Element element;
                                Element groupIdElement;
                                while(true) {
                                    do {
                                        if (!i$.hasNext()) {
                                            return;
                                        }

                                        element = (Element)i$.next();
                                        versionElement = element.getChild("version", element.getNamespace());
                                    } while(versionElement == null);

                                    rawVersion = versionElement.getTextTrim();
                                    groupIdElement = element.getChild("groupId", element.getNamespace());
                                    if (groupIdElement != null) {
                                        break;
                                    }

                                    if ("plugin".equals(element.getName())) {
                                        groupIdElement = new Element("groupId", element.getNamespace());
                                        groupIdElement.setText("org.apache.maven.plugins");
                                        break;
                                    }
                                }

                                groupId = ReleaseUtil.interpolate(groupIdElement.getTextTrim(), projectModel);
                                artifactIdElement = element.getChild("artifactId", element.getNamespace());
                            } while(artifactIdElement == null);

                            artifactId = ReleaseUtil.interpolate(artifactIdElement.getTextTrim(), projectModel);
                            key = ArtifactUtils.versionlessKey(groupId, artifactId);
                            resolvedSnapshotVersion = this.getResolvedSnapshotVersion(key, resolvedSnapshotDependencies);
                            mappedVersion = (String)mappedVersions.get(key);
                            originalVersion = (String)originalVersions.get(key);
                            if (originalVersion == null) {
                                originalVersion = this.getOriginalResolvedSnapshotVersion(key, resolvedSnapshotDependencies);
                            }
                        } while(mappedVersion != null && mappedVersion.endsWith("SNAPSHOT") && !rawVersion.endsWith("SNAPSHOT") && !releaseDescriptor.isUpdateDependencies());

                        if (mappedVersion != null) {
                            if (rawVersion.equals(originalVersion)) {
                                this.logInfo(result, "  Updating " + artifactId + " to " + mappedVersion);
                                this.rewriteValue(versionElement, mappedVersion);
                            } else if (rawVersion.matches("\\$\\{.+\\}")) {
                                String expression = rawVersion.substring(2, rawVersion.length() - 1);
                                if (!expression.startsWith("project.") && !expression.startsWith("pom.") && !"version".equals(expression)) {
                                    if (properties != null) {
                                        Element property = properties.getChild(expression, properties.getNamespace());
                                        if (property == null) {
                                            //不直接抛出异常，只是输出信息
                                            //throw new ReleaseFailureException("The version could not be updated: " + rawVersion);
                                            this.logInfo(result,"The version could not be updated: " + rawVersion);
                                        }else {

                                            String propertyValue = property.getTextTrim();
                                            if (propertyValue.equals(originalVersion)) {
                                                this.logInfo(result,
                                                        "  Updating " + rawVersion + " to "
                                                                + mappedVersion);
                                                this.rewriteValue(property, mappedVersion);
                                            } else if (mappedVersion.equals(propertyValue)) {
                                                this.logInfo(result,
                                                        "  Ignoring artifact version update for expression "
                                                                + rawVersion
                                                                + " because it is already updated");
                                            } else if (!mappedVersion.equals(rawVersion)) {
                                                if (!mappedVersion.matches("\\$\\{project.+\\}")
                                                        && !mappedVersion.matches("\\$\\{pom.+\\}")
                                                        && !"${version}".equals(mappedVersion)) {
                                                    throw new ReleaseFailureException(
                                                            "The artifact (" + key + ") requires a "
                                                                    + "different version ("
                                                                    + mappedVersion
                                                                    + ") than what is found ("
                                                                    + propertyValue
                                                                    + ") for the expression ("
                                                                    + expression + ") in the "
                                                                    + "project (" + projectId
                                                                    + ").");
                                                }

                                                this.logInfo(result,
                                                        "  Ignoring artifact version update for expression "
                                                                + mappedVersion);
                                            }
                                        }
                                    }
                                } else if (!mappedVersion.equals(mappedVersions.get(projectId))) {
                                    this.logInfo(result, "  Updating " + artifactId + " to " + mappedVersion);
                                    this.rewriteValue(versionElement, mappedVersion);
                                } else {
                                    this.logInfo(result, "  Ignoring artifact version update for expression " + rawVersion);
                                }
                            }
                        } else if (resolvedSnapshotVersion != null) {
                            this.logInfo(result, "  Updating " + artifactId + " to " + resolvedSnapshotVersion);
                            this.rewriteValue(versionElement, resolvedSnapshotVersion);
                        }
                    }
                }
            }
        }
    }

    private void writePom(File pomFile, Document document, ReleaseDescriptor releaseDescriptor, String modelVersion, String intro, String outtro, ScmRepository repository, ScmProvider provider) throws ReleaseExecutionException, ReleaseScmCommandException {
        try {
            if (this.isUpdateScm() && (releaseDescriptor.isScmUseEditMode() || provider.requiresEditMode())) {
                EditScmResult result = provider.edit(repository, new ScmFileSet(new File(releaseDescriptor.getWorkingDirectory()), pomFile));
                if (!result.isSuccess()) {
                    throw new ReleaseScmCommandException("Unable to enable editing on the POM", result);
                }
            }
        } catch (ScmException var10) {
            throw new ReleaseExecutionException("An error occurred enabling edit mode: " + var10.getMessage(), var10);
        }

        this.writePom(pomFile, document, releaseDescriptor, modelVersion, intro, outtro);
    }

    private void writePom(File pomFile, Document document, ReleaseDescriptor releaseDescriptor, String modelVersion, String intro, String outtro) throws ReleaseExecutionException {
        Element rootElement = document.getRootElement();
        if (releaseDescriptor.isAddSchema()) {
            Namespace pomNamespace = Namespace.getNamespace("", "http://maven.apache.org/POM/" + modelVersion);
            rootElement.setNamespace(pomNamespace);
            Namespace xsiNamespace = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
            rootElement.addNamespaceDeclaration(xsiNamespace);
            if (rootElement.getAttribute("schemaLocation", xsiNamespace) == null) {
                rootElement.setAttribute("schemaLocation", "http://maven.apache.org/POM/" + modelVersion + " http://maven.apache.org/maven-v" + modelVersion.replace('.', '_') + ".xsd", xsiNamespace);
            }

            ElementFilter elementFilter = new ElementFilter(Namespace.getNamespace(""));
            Iterator i = rootElement.getDescendants(elementFilter);

            while(i.hasNext()) {
                Element e = (Element)i.next();
                e.setNamespace(pomNamespace);
            }
        }

        XmlStreamWriter writer = null;

        try {
            writer = WriterFactory.newXmlWriter(pomFile);
            if (intro != null) {
                writer.write(intro);
            }

            Format format = Format.getRawFormat();
            format.setLineSeparator(this.ls);
            XMLOutputter out = new XMLOutputter(format);
            out.output(document.getRootElement(), writer);
            if (outtro != null) {
                writer.write(outtro);
            }
        } catch (IOException var16) {
            throw new ReleaseExecutionException("Error writing POM: " + var16.getMessage(), var16);
        } finally {
            IOUtil.close(writer);
        }

    }

    public ReleaseResult simulate(ReleaseDescriptor releaseDescriptor, ReleaseEnvironment releaseEnvironment, List<MavenProject> reactorProjects) throws ReleaseExecutionException, ReleaseFailureException {
        ReleaseResult result = new ReleaseResult();
        this.transform(releaseDescriptor, releaseEnvironment, reactorProjects, true, result);
        result.setResultCode(0);
        return result;
    }

    public ReleaseResult clean(List<MavenProject> reactorProjects) {
        ReleaseResult result = new ReleaseResult();
        super.clean(reactorProjects);
        if (reactorProjects != null) {
            Iterator i$ = reactorProjects.iterator();

            while(i$.hasNext()) {
                MavenProject project = (MavenProject)i$.next();
                File pomFile = ReleaseUtil.getStandardPom(project);
                if (pomFile != null) {
                    File file = new File(pomFile.getParentFile(), pomFile.getName() + "." + this.pomSuffix);
                    if (file.exists()) {
                        file.delete();
                    }
                }
            }
        }

        result.setResultCode(0);
        return result;
    }

    protected abstract String getResolvedSnapshotVersion(String var1, Map<String, Map<String, String>> var2);

    protected abstract Map<String, String> getOriginalVersionMap(ReleaseDescriptor var1, List<MavenProject> var2, boolean var3);

    protected abstract Map<String, String> getNextVersionMap(ReleaseDescriptor var1);

    protected abstract void transformScm(MavenProject var1, Element var2, Namespace var3, ReleaseDescriptor var4, String var5, ScmRepository var6, ReleaseResult var7, String var8) throws ReleaseExecutionException;

    protected boolean isUpdateScm() {
        return true;
    }

    protected String getOriginalResolvedSnapshotVersion(String artifactVersionlessKey, Map<String, Map<String, String>> resolvedSnapshots) {
        Map<String, String> versionsMap = (Map)resolvedSnapshots.get(artifactVersionlessKey);
        return versionsMap != null ? (String)versionsMap.get("original") : null;
    }

    protected Element rewriteElement(String name, String value, Element root, Namespace namespace) {
        Element tagElement = root.getChild(name, namespace);
        if (tagElement != null) {
            if (value != null) {
                this.rewriteValue(tagElement, value);
            } else {
                int index = root.indexOf(tagElement);
                root.removeContent(index);

                for(int i = index - 1; i >= 0 && root.getContent(i) instanceof Text; --i) {
                    root.removeContent(i);
                }
            }
        } else if (value != null) {
            Element element = new Element(name, namespace);
            element.setText(value);
            root.addContent("  ").addContent(element).addContent("\n  ");
            tagElement = element;
        }

        return tagElement;
    }

    protected Scm buildScm(MavenProject project) {
        IdentifiedScm scm;
        if (project.getOriginalModel().getScm() == null) {
            scm = null;
        } else {
            scm = new IdentifiedScm();
            scm.setConnection(project.getOriginalModel().getScm().getConnection());
            scm.setDeveloperConnection(project.getOriginalModel().getScm().getDeveloperConnection());
            scm.setTag(project.getOriginalModel().getScm().getTag());
            scm.setUrl(project.getOriginalModel().getScm().getUrl());
            scm.setId(project.getProperties().getProperty("project.scm.id"));
        }

        return scm;
    }

    protected static String translateUrlPath(String trunkPath, String tagPath, String urlPath) {
        trunkPath = trunkPath.trim();
        tagPath = tagPath.trim();
        if (trunkPath.endsWith("/")) {
            trunkPath = trunkPath.substring(0, trunkPath.length() - 1);
        }

        if (tagPath.endsWith("/")) {
            tagPath = tagPath.substring(0, tagPath.length() - 1);
        }

        char[] tagPathChars = trunkPath.toCharArray();
        char[] trunkPathChars = tagPath.toCharArray();

        int i;
        for(i = 0; i < tagPathChars.length && i < trunkPathChars.length && tagPathChars[i] == trunkPathChars[i]; ++i) {
            ;
        }

        return i != 0 && urlPath.indexOf(trunkPath.substring(i)) >= 0 ? StringUtils.replace(urlPath, trunkPath.substring(i), tagPath.substring(i)) : tagPath;
    }
}