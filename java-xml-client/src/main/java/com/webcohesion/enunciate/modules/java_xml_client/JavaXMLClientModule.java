/**
 * Copyright © 2006-2016 Web Cohesion (info@webcohesion.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webcohesion.enunciate.modules.java_xml_client;

import com.webcohesion.enunciate.Enunciate;
import com.webcohesion.enunciate.EnunciateContext;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.api.DefaultRegistrationContext;
import com.webcohesion.enunciate.api.resources.MediaTypeDescriptor;
import com.webcohesion.enunciate.api.resources.Method;
import com.webcohesion.enunciate.api.resources.Resource;
import com.webcohesion.enunciate.api.resources.ResourceGroup;
import com.webcohesion.enunciate.artifacts.ArtifactType;
import com.webcohesion.enunciate.artifacts.ClientLibraryJavaArtifact;
import com.webcohesion.enunciate.artifacts.FileArtifact;
import com.webcohesion.enunciate.facets.FacetFilter;
import com.webcohesion.enunciate.javac.decorations.SourcePosition;
import com.webcohesion.enunciate.metadata.DocumentationExample;
import com.webcohesion.enunciate.module.*;
import com.webcohesion.enunciate.modules.jaxb.EnunciateJaxbContext;
import com.webcohesion.enunciate.modules.jaxb.JaxbModule;
import com.webcohesion.enunciate.modules.jaxb.api.impl.SyntaxImpl;
import com.webcohesion.enunciate.modules.jaxb.model.QNameEnumTypeDefinition;
import com.webcohesion.enunciate.modules.jaxb.model.Registry;
import com.webcohesion.enunciate.modules.jaxb.model.SchemaInfo;
import com.webcohesion.enunciate.modules.jaxb.model.TypeDefinition;
import com.webcohesion.enunciate.modules.jaxb.model.util.JAXBCodeErrors;
import com.webcohesion.enunciate.modules.jaxrs.JaxrsModule;
import com.webcohesion.enunciate.modules.jaxws.JaxwsModule;
import com.webcohesion.enunciate.modules.jaxws.WsdlInfo;
import com.webcohesion.enunciate.modules.jaxws.model.*;
import com.webcohesion.enunciate.util.AntPatternMatcher;
import com.webcohesion.enunciate.util.freemarker.*;
import freemarker.cache.URLTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.commons.configuration2.HierarchicalConfiguration;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * @author Ryan Heaton
 */
public class JavaXMLClientModule extends BasicGeneratingModule implements ApiFeatureProviderModule, ProjectExtensionModule {

  private static final String LIRBARY_DESCRIPTION_PROPERTY = "com.webcohesion.enunciate.modules.java_xml_client.EnunciateJavaXMLClientModule#LIRBARY_DESCRIPTION_PROPERTY";

  JaxbModule jaxbModule;
  JaxwsModule jaxwsModule;
  JaxrsModule jaxrsModule;

  /**
   * @return "java-xml"
   */
  @Override
  public String getName() {
    return "java-xml-client";
  }

  @Override
  public List<DependencySpec> getDependencySpecifications() {
    return List.of(new DependencySpec() {
      @Override
      public boolean accept(EnunciateModule module) {
        if (module instanceof JaxbModule) {
          jaxbModule = (JaxbModule) module;
          return true;
        }
        else if (module instanceof JaxwsModule) {
          jaxwsModule = (JaxwsModule) module;
          return true;
        }
        else if (module instanceof JaxrsModule) {
          jaxrsModule = (JaxrsModule) module;
          return true;
        }

        return module instanceof ApiRegistryProviderModule;
      }

      @Override
      public boolean isFulfilled() {
        return true;
      }

      @Override
      public String toString() {
        return "optional jaxb, optional jaxws, optional jaxrs";
      }
    });
  }


  @Override
  public void call(EnunciateContext context) {
    if (this.jaxbModule == null || this.jaxbModule.getJaxbContext() == null || this.jaxbModule.getJaxbContext().getSchemas().isEmpty()) {
      info("No JAXB XML data types: Java XML client will not be generated.");
      return;
    }

    List<String> namingConflicts = JAXBCodeErrors.findConflictingAccessorNamingErrors(this.jaxbModule.getJaxbContext());
    if (namingConflicts != null && !namingConflicts.isEmpty()) {
      error("JAXB naming conflicts have been found:");
      for (String namingConflict : namingConflicts) {
        error(namingConflict);
      }
      error("These naming conflicts are often between the field and it's associated property, in which case you need to use one or two of the following strategies to avoid the conflicts:");
      error("1. Explicitly exclude one or the other.");
      error("2. Put the annotations on the property instead of the field.");
      error("3. Tell JAXB to use a different process for detecting accessors using the @XmlAccessorType annotation.");
      throw new EnunciateException("JAXB naming conflicts detected.");
    }

    File sourceDir = generateClientSources();
    File compileDir = compileClientSources(sourceDir);
    File resourcesDir = copyResources();

    packageArtifacts(sourceDir, resourcesDir, compileDir);
  }

  protected File generateClientSources() {
    File sourceDir = getSourceDir();
    sourceDir.mkdirs();

    Map<String, Object> model = new HashMap<>();

    Map<String, String> conversions = getClientPackageConversions();
    EnunciateJaxbContext jaxbContext = this.jaxbModule.getJaxbContext();
    model.put("packageFor", new ClientPackageForMethod(conversions, this.context));
    model.put("classnameFor", new ClientClassnameForMethod(conversions, jaxbContext));
    model.put("simpleNameFor", new SimpleNameForMethod(new ClientClassnameForMethod(conversions, jaxbContext, true)));
    model.put("file", new FileDirective(sourceDir, this.enunciate.getLogger()));
    model.put("generatedCodeLicense", this.enunciate.getConfiguration().readGeneratedCodeLicenseFile());
    model.put("annotationValue", new AnnotationValueMethod());

    FacetFilter facetFilter = createFacetFilter();
    model.put("isFacetExcluded", new IsFacetExcludedMethod(facetFilter));

    if (isUpToDateWithSources(sourceDir)) {
      info("Skipping generation of Java client sources as everything appears up-to-date...");
    }
    else {
      generateClientSources(sourceDir, model, conversions, jaxbContext, facetFilter);
    }

    context.setProperty(LIRBARY_DESCRIPTION_PROPERTY, readLibraryDescription(model));

    return sourceDir;
  }

  private FacetFilter createFacetFilter() {
    Set<String> facetIncludes = new TreeSet<>(this.enunciate.getConfiguration().getFacetIncludes());
    facetIncludes.addAll(getFacetIncludes());
    Set<String> facetExcludes = new TreeSet<>(this.enunciate.getConfiguration().getFacetExcludes());
    facetExcludes.addAll(getFacetExcludes());
    return new FacetFilter(facetIncludes, facetExcludes);
  }

  private void generateClientSources(File sourceDir, Map<String, Object> model, Map<String, String> conversions, EnunciateJaxbContext jaxbContext, FacetFilter facetFilter) {
    try {
      debug("Generating the Java client classes...");

      HashMap<String, WebFault> allFaults = new HashMap<>();
      AntPatternMatcher matcher = new AntPatternMatcher();
      matcher.setPathSeparator(".");
      ClientClassnameForMethod classnameFor = new ClientClassnameForMethod(conversions, jaxbContext);

      generateJaxwsSources(sourceDir, model, facetFilter, matcher, classnameFor, allFaults);
      generateJaxbSources(sourceDir, model, facetFilter, matcher);
    }
    catch (IOException | TemplateException e) {
      throw new EnunciateException(e);
    }
  }

  private void generateJaxwsSources(File sourceDir, Map<String, Object> model, FacetFilter facetFilter, AntPatternMatcher matcher, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults) throws IOException, TemplateException {
    if (this.jaxwsModule == null) {
      return;
    }

    Set<String> seeAlsos = new TreeSet<>();
    // Process the annotations, the request/response beans, and gather the set of web faults
    // for each endpoint interface.
    for (WsdlInfo wsdlInfo : this.jaxwsModule.getJaxwsContext().getWsdls().values()) {
      addEndpointSources(model, facetFilter, classnameFor, allFaults, seeAlsos, wsdlInfo);
    }

    //gather the annotation information and process the possible beans for each web fault.
    addFaultBeans(model, classnameFor, allFaults, seeAlsos);
    model.put("seeAlsoBeans", seeAlsos);
    model.put("baseUri", this.enunciate.getConfiguration().getApplicationRoot());
    generateWebFaultSources(sourceDir, model, matcher, allFaults);
  }

  private void addEndpointSources(Map<String, Object> model, FacetFilter facetFilter, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults, Set<String> seeAlsos, WsdlInfo wsdlInfo) throws IOException, TemplateException {
    validateWsdlFile(wsdlInfo);
    for (EndpointInterface endpointInterface : wsdlInfo.getEndpointInterfaces()) {
      addEndpointSource(model, facetFilter, classnameFor, allFaults, seeAlsos, wsdlInfo, endpointInterface);
    }
  }

  private void validateWsdlFile(WsdlInfo wsdlInfo) {
    if (wsdlInfo.getWsdlFile() == null) {
      throw new EnunciateException("WSDL " + wsdlInfo.getId() + " doesn't have a filename.");
    }
  }

  private void addEndpointSource(Map<String, Object> model, FacetFilter facetFilter, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults, Set<String> seeAlsos, WsdlInfo wsdlInfo, EndpointInterface endpointInterface) throws IOException, TemplateException {
    if (!facetFilter.accept(endpointInterface)) {
      return;
    }

    collectEndpointMessages(model, classnameFor, allFaults, seeAlsos, endpointInterface, facetFilter);
    model.put("endpointInterface", endpointInterface);
    model.put("wsdlFileName", wsdlInfo.getFilename());
    processTemplate(getTemplateURL("client-endpoint-interface.fmt"), model);
    processTemplate(getTemplateURL("client-soap-endpoint-impl.fmt"), model);
  }

  private void collectEndpointMessages(Map<String, Object> model, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults, Set<String> seeAlsos, EndpointInterface endpointInterface, FacetFilter facetFilter) throws IOException, TemplateException {
    for (WebMethod webMethod : endpointInterface.getWebMethods()) {
      collectWebMethodMessages(model, classnameFor, allFaults, seeAlsos, webMethod, facetFilter);
    }
  }

  private void collectWebMethodMessages(Map<String, Object> model, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults, Set<String> seeAlsos, WebMethod webMethod, FacetFilter facetFilter) throws IOException, TemplateException {
    if (!facetFilter.accept(webMethod)) {
      return;
    }

    for (WebMessage webMessage : webMethod.getMessages()) {
      collectWebMessage(model, classnameFor, allFaults, seeAlsos, webMessage);
    }
  }

  private void collectWebMessage(Map<String, Object> model, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults, Set<String> seeAlsos, WebMessage webMessage) throws IOException, TemplateException {
    if (webMessage instanceof RequestWrapper requestWrapper) {
      model.put("message", webMessage);
      processTemplate(getTemplateURL("client-request-bean.fmt"), model);
      seeAlsos.add(getBeanName(classnameFor, requestWrapper.getRequestBeanName()));
      return;
    }

    if (webMessage instanceof ResponseWrapper responseWrapper) {
      model.put("message", webMessage);
      processTemplate(getTemplateURL("client-response-bean.fmt"), model);
      seeAlsos.add(getBeanName(classnameFor, responseWrapper.getResponseBeanName()));
      return;
    }

    if (webMessage instanceof WebFault fault) {
      allFaults.put(fault.getQualifiedName().toString(), fault);
    }
  }

  private void addFaultBeans(Map<String, Object> model, ClientClassnameForMethod classnameFor, Map<String, WebFault> allFaults, Set<String> seeAlsos) throws IOException, TemplateException {
    for (WebFault webFault : allFaults.values()) {
      boolean implicit = webFault.isImplicitSchemaElement();
      String faultBean = implicit ? getBeanName(classnameFor, webFault.getImplicitFaultBeanQualifiedName()) : classnameFor.convert(webFault.getExplicitFaultBeanType());
      seeAlsos.add(faultBean);
      if (!implicit) {
        continue;
      }

      model.put("fault", webFault);
      processTemplate(getTemplateURL("client-fault-bean.fmt"), model);
    }
  }

  private void generateWebFaultSources(File sourceDir, Map<String, Object> model, AntPatternMatcher matcher, Map<String, WebFault> allFaults) throws IOException, TemplateException {
    for (WebFault webFault : allFaults.values()) {
      if (useServerSide(webFault, matcher)) {
        copyServerSideType(sourceDir, webFault);
      }
      else {
        putSuperFault(model, allFaults, webFault);
        model.put("fault", webFault);
        processTemplate(getTemplateURL("client-web-fault.fmt"), model);
      }
    }
  }

  private void putSuperFault(Map<String, Object> model, Map<String, WebFault> allFaults, WebFault webFault) {
    TypeElement superFault = (TypeElement) ((DeclaredType) webFault.getSuperclass()).asElement();
    WebFault parentFault = superFault == null ? null : allFaults.get(superFault.getQualifiedName().toString());
    if (parentFault != null && parentFault.isImplicitSchemaElement()) {
      model.put("superFault", parentFault);
    }
    else {
      model.remove("superFault");
    }
  }

  private void generateJaxbSources(File sourceDir, Map<String, Object> model, FacetFilter facetFilter, AntPatternMatcher matcher) throws IOException, TemplateException {
    for (SchemaInfo schemaInfo : this.jaxbModule.getJaxbContext().getSchemas().values()) {
      generateSchemaSources(sourceDir, model, facetFilter, matcher, schemaInfo);
    }
  }

  private void generateSchemaSources(File sourceDir, Map<String, Object> model, FacetFilter facetFilter, AntPatternMatcher matcher, SchemaInfo schemaInfo) throws IOException, TemplateException {
    for (TypeDefinition typeDefinition : schemaInfo.getTypeDefinitions()) {
      generateTypeDefinitionSource(sourceDir, model, facetFilter, matcher, typeDefinition);
    }

    for (Registry registry : schemaInfo.getRegistries()) {
      model.put("registry", registry);
      processTemplate(getTemplateURL("client-registry.fmt"), model);
    }
  }

  private void generateTypeDefinitionSource(File sourceDir, Map<String, Object> model, FacetFilter facetFilter, AntPatternMatcher matcher, TypeDefinition typeDefinition) throws IOException, TemplateException {
    if (!facetFilter.accept(typeDefinition)) {
      return;
    }

    if (useServerSide(typeDefinition, matcher)) {
      copyServerSideType(sourceDir, typeDefinition);
      return;
    }

    model.put("rootEl", this.jaxbModule.getJaxbContext().findElementDeclaration(typeDefinition));
    model.put("type", typeDefinition);
    processTemplate(getTypeDefinitionTemplate(typeDefinition), model);
  }

  private URL getTypeDefinitionTemplate(TypeDefinition typeDefinition) {
    if (typeDefinition.isEnum()) {
      if (typeDefinition instanceof QNameEnumTypeDefinition) {
        return getTemplateURL("client-qname-enum-type.fmt");
      }
      return getTemplateURL("client-enum-type.fmt");
    }

    if (typeDefinition.isSimple()) {
      return getTemplateURL("client-simple-type.fmt");
    }

    return getTemplateURL("client-complex-type.fmt");
  }

  protected void copyServerSideType(File sourceDir, TypeElement type) throws IOException {
    SourcePosition source = this.context.getProcessingEnvironment().findSourcePosition(type);
    JavaFileObject sourceFile = source.getSourceFile();
    File destFile = getServerSideDestFile(sourceDir, sourceFile, type);
    FileWriter writer = new FileWriter(destFile);
    debug("Writing server-side java type to %s.", destFile);
    writer.write(sourceFile.getCharContent(false).toString());
    writer.flush();
    writer.close();
  }

  protected File getSourceDir() {
    return new File(new File(this.enunciate.getBuildDir(), getName()), "src");
  }

  /**
   * Processes the specified template with the given model.
   *
   * @param templateURL The template URL.
   * @param model       The root model.
   */
  public String processTemplate(URL templateURL, Object model) throws IOException, TemplateException {
    debug("Processing template %s.", templateURL);
    Configuration configuration = new Configuration(FreemarkerUtil.VERSION);
    configuration.setLocale(new Locale("en", "US"));

    configuration.setTemplateLoader(new URLTemplateLoader() {
      protected URL getURL(String name) {
        try {
          return new URL(name);
        }
        catch (MalformedURLException e) {
          return null;
        }
      }
    });

    configuration.setTemplateExceptionHandler((templateException, environment, writer) -> {
      throw templateException;
    });

    configuration.setLocalizedLookup(false);
    configuration.setDefaultEncoding("UTF-8");
    configuration.setObjectWrapper(new JavaXMLClientObjectWrapper());
    Template template = configuration.getTemplate(templateURL.toString());
    StringWriter unhandledOutput = new StringWriter();
    template.process(model, unhandledOutput);
    unhandledOutput.close();
    return unhandledOutput.toString();
  }

  protected File getServerSideDestFile(File sourceDir, JavaFileObject sourceFile, TypeElement declaration) {
    File destDir = sourceDir;
    String packageName = this.context.getProcessingEnvironment().getElementUtils().getPackageOf(declaration).getQualifiedName().toString();
    for (StringTokenizer packagePaths = new StringTokenizer(packageName, "."); packagePaths.hasMoreTokens();) {
      String packagePath = packagePaths.nextToken();
      destDir = new File(destDir, packagePath);
    }
    destDir.mkdirs();
    String simpleFilename = sourceFile.toUri().toString();
    simpleFilename = simpleFilename.substring(simpleFilename.lastIndexOf('/'));
    return new File(destDir, simpleFilename);
  }

  /**
   * Whether to use the server-side declaration for this declaration.
   *
   * @param declaration The declaration.
   * @param matcher     The matcher.
   * @return Whether to use the server-side declaration for this declaration.
   */
  protected boolean useServerSide(TypeElement declaration, AntPatternMatcher matcher) {
    boolean useServerSide = false;

    for (String pattern : getServerSideTypesToUse()) {
      if (matcher.match(pattern, declaration.getQualifiedName().toString())) {
        useServerSide = true;
        break;
      }
    }
    return useServerSide;
  }

  /**
   * Get the bean name for a specified string.
   *
   * @param conversion The conversion to use.
   * @param preconvert The pre-converted fqn.
   * @return The converted fqn.
   */
  protected String getBeanName(ClientClassnameForMethod conversion, String preconvert) {
    String pckg = conversion.convert(preconvert.substring(0, preconvert.lastIndexOf('.')));
    String simpleName = preconvert.substring(preconvert.lastIndexOf('.') + 1);
    return pckg + "." + simpleName;
  }

  protected File compileClientSources(File sourceDir) {
    File compileDir = getCompileDir();
    compileDir.mkdirs();

    //Compile the java files.
    if (!isDisableCompile()) {
      if (!isUpToDateWithSources(compileDir)) {
        List<File> sources = findJavaFiles(sourceDir);
        if (sources != null && !sources.isEmpty()) {
          String classpath = this.enunciate.writeClasspath(enunciate.getClasspath());
          JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
          List<String> options = Arrays.asList("-source", getJavacSource(), "-target", getJavacTarget(), "-encoding", "UTF-8", "-cp", classpath, "-d", compileDir.getAbsolutePath(), "-nowarn");
          JavaCompiler.CompilationTask task = compiler.getTask(null, null, null, options, null, compiler.getStandardFileManager(null, null, null).getJavaFileObjectsFromFiles(sources));
          if (!task.call()) {
            throw new EnunciateException("Compile failed of Java client-side classes.");
          }
        }
        else {
          debug("No Java XML client classes to compile.");
        }
      }
      else {
        info("Skipping compilation of Java client classes as everything appears up-to-date...");
      }
    }

    return compileDir;

  }

  private List<File> findJavaFiles(File sourceDir) {
    final ArrayList<File> javaFiles = new ArrayList<>();
    this.enunciate.visitFiles(sourceDir, Enunciate.JAVA_FILTER, javaFiles::add);
    return javaFiles;
  }

  protected File getCompileDir() {
    return new File(new File(this.enunciate.getBuildDir(), getName()), "classes");
  }

  protected File copyResources() {
    File resourcesDir = getResourcesDir();
    resourcesDir.mkdirs();

    try {
      if (this.jaxwsModule != null) {
        for (WsdlInfo wsdlInfo : this.jaxwsModule.getJaxwsContext().getWsdls().values()) {
          if (wsdlInfo.getWsdlFile() != null) {
            wsdlInfo.getWsdlFile().writeTo(resourcesDir);
          }
        }
      }

      for (SchemaInfo schemaInfo : this.jaxbModule.getJaxbContext().getSchemas().values()) {
        if (schemaInfo.getSchemaFile() != null) {
          schemaInfo.getSchemaFile().writeTo(resourcesDir);
        }
      }
    }
    catch (IOException e) {
      throw new EnunciateException(e);
    }

    return resourcesDir;

  }

  protected File getResourcesDir() {
    return new File(new File(this.enunciate.getBuildDir(), getName()), "resources");
  }

  protected File packageArtifacts(File sourceDir, File resourcesDir, File compileDir) {
    File packageDir = getPackageDir();
    packageDir.mkdirs();

    try {
      String jarName = getJarName();

      File clientJarFile = null;
      if (!isDisableCompile()) {
        clientJarFile = new File(packageDir, jarName);
        if (!isUpToDateWithSources(clientJarFile)) {
          if (isBundleSourcesWithClasses()) {
            boolean anyFiles = this.enunciate.jar(clientJarFile, getManifest(), sourceDir, resourcesDir, compileDir);
            if (!anyFiles) {
              clientJarFile = null;
            }
          }
          else {
            boolean anyFiles = this.enunciate.jar(clientJarFile, getManifest(), resourcesDir, compileDir);
            if (!anyFiles) {
              clientJarFile = null;
            }
          }
        }
        else {
          info("Skipping creation of Java client jar as everything appears up-to-date...");
        }
      }

      File clientSourcesJarFile = null;
      if (!isBundleSourcesWithClasses()) {
        clientSourcesJarFile = new File(packageDir, jarName.replaceFirst("\\.jar", "-xml-sources.jar"));
        if (!isUpToDateWithSources(clientSourcesJarFile)) {
          boolean anyFiles = this.enunciate.zip(clientSourcesJarFile, sourceDir, resourcesDir);
          if (!anyFiles) {
            clientSourcesJarFile = null;
          }
        }
        else {
          info("Skipping creation of the Java client source jar as everything appears up-to-date...");
        }
      }

      ClientLibraryJavaArtifact artifactBundle = new ClientLibraryJavaArtifact(getName(), "java.xml.client.library", "Java XML Client Library");
      artifactBundle.setGroupId(getGroupId());
      artifactBundle.setArtifactId(getArtifactId());
      artifactBundle.setVersion(getVersion());
      artifactBundle.setPlatform("Java (Version 5+)");
      //read in the description from file:
      artifactBundle.setDescription((String) context.getProperty(LIRBARY_DESCRIPTION_PROPERTY));
      if (clientJarFile != null) {
        FileArtifact binariesJar = new FileArtifact(getName(), "java.xml.client.library.binaries", clientJarFile);
        binariesJar.setDescription("The binaries for the Java XML client library.");
        binariesJar.setPublic(false);
        binariesJar.setArtifactType(ArtifactType.binaries);
        artifactBundle.addArtifact(binariesJar);
        this.enunciate.addArtifact(binariesJar);
      }

      if (clientSourcesJarFile != null) {
        FileArtifact sourcesJar = new FileArtifact(getName(), "java.xml.client.library.sources", clientSourcesJarFile);
        sourcesJar.setDescription("The sources for the Java XML client library.");
        sourcesJar.setPublic(false);
        sourcesJar.setArtifactType(ArtifactType.sources);
        artifactBundle.addArtifact(sourcesJar);
        this.enunciate.addArtifact(sourcesJar);
      }

      if (clientJarFile != null || clientSourcesJarFile != null) {
        this.enunciate.addArtifact(artifactBundle);
      }
    }
    catch (IOException e) {
      throw new EnunciateException(e);
    }

    return packageDir;
  }

  protected File getPackageDir() {
    return new File(new File(this.enunciate.getBuildDir(), getName()), "build");
  }

  /**
   * Reads a resource into string form.
   *
   * @return The string form of the resource.
   */
  protected String readLibraryDescription(Map<String, Object> model) {
    model.put("sample_service_method", findExampleWebMethod());
    model.put("sample_resource", findExampleResourceMethod());
    model.put("mediaTypeFor", new MediaTypeForMethod());

    URL res = JavaXMLClientModule.class.getResource("library_description.fmt");
    try {
      return processTemplate(res, model);
    }
    catch (TemplateException | IOException e) {
      throw new EnunciateException(e);
    }
  }

  /**
   * Finds an example resource method, according to the following preference order:
   *
   * <ol>
   * <li>The first method annotated with {@link DocumentationExample}.
   * <li>The first web method that returns a declared type.
   * <li>The first web method.
   * </ol>
   *
   * @return An example resource method, or if no good examples were found.
   */
  public WebMethod findExampleWebMethod() {
    WebMethod example = null;
    if (this.jaxwsModule != null) {
      for (EndpointInterface ei : this.jaxwsModule.getJaxwsContext().getEndpointInterfaces()) {
        for (WebMethod method : ei.getWebMethods()) {
          if (method.getAnnotation(DocumentationExample.class) != null && !method.getAnnotation(DocumentationExample.class).exclude()) {
            return method;
          }
          else if (method.getJavaDoc().get("documentationExample") != null) {
            return method;
          }
          else if (method.getWebResult() != null && method.getWebResult().getType() instanceof DeclaredType
            && (example == null || example.getWebResult() == null || (!(example.getWebResult().getType() instanceof DeclaredType)))) {
            example = method;
          }
          else {
            //we'll prefer the first one we find with an output.
            example = example == null ? method : example;
          }
        }
      }
    }
    return example;
  }

  /**
   * Finds an example resource method, according to the following preference order:
   *
   * <ol>
   * <li>The first method annotated with {@link DocumentationExample}.
   * <li>The first method with BOTH an output payload with a known XML element and an input payload with a known XML element.
   * <li>The first method with an output payload with a known XML element.
   * </ol>
   *
   * @return An example resource method, or if no good examples were found.
   */
  public Method findExampleResourceMethod() {
    Method example = null;
    if (this.jaxrsModule != null) {
      List<ResourceGroup> resourceGroups = this.jaxrsModule.getJaxrsContext().getResourceGroups(new DefaultRegistrationContext(context));
      for (ResourceGroup resourceGroup : resourceGroups) {
        List<Resource> resources = resourceGroup.getResources();
        for (Resource resource : resources) {
          for (Method method : resource.getMethods()) {
            if (hasXmlResponseEntity(method)) {
              if (hasXmlRequestEntity(method)) {
                //we'll prefer one with both an output AND an input.
                return method;
              }
              else {
                //we'll prefer the first one we find with an output.
                example = example == null ? method : example;
              }
            }
          }
        }
      }
    }

    return example;
  }

  private boolean hasXmlResponseEntity(Method method) {
    if (method.getResponseEntity() != null) {
      for (MediaTypeDescriptor mediaTypeDescriptor : method.getResponseEntity().getMediaTypes()) {
        if (SyntaxImpl.SYNTAX_LABEL.equals(mediaTypeDescriptor.getSyntax())) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean hasXmlRequestEntity(Method method) {
    if (method.getRequestEntity() != null) {
      for (MediaTypeDescriptor mediaTypeDescriptor : method.getRequestEntity().getMediaTypes()) {
        if (SyntaxImpl.SYNTAX_LABEL.equals(mediaTypeDescriptor.getSyntax())) {
          return true;
        }
      }
    }
    return false;
  }

  protected URL getTemplateURL(String template) {
    return JavaXMLClientModule.class.getResource(template);
  }

  public String getJarName() {
    return this.config.getString("[@jarName]", getSlug() + "-xml-client.jar");
  }

  public Map<String, String> getClientPackageConversions() {
    List<HierarchicalConfiguration> conversionElements = this.config.configurationsAt("package-conversions.convert");
    HashMap<String, String> conversions = new HashMap<String, String>();
    conversions.put("java.lang.Exception", "client.java.lang.Exception");

    for (HierarchicalConfiguration conversionElement : conversionElements) {
      conversions.put(conversionElement.getString("[@from]"), conversionElement.getString("[@to]"));
    }
    return conversions;
  }

  public Set<String> getServerSideTypesToUse() {
    List<HierarchicalConfiguration> typeElements = this.config.configurationsAt("server-side-type");
    TreeSet<String> types = new TreeSet<>();
    for (HierarchicalConfiguration typeElement : typeElements) {
      types.add(typeElement.getString("[@pattern]"));
    }
    return types;
  }

  public String getSlug() {
    return this.config.getString("[@slug]", this.enunciate.getConfiguration().getSlug());
  }

  public boolean isBundleSourcesWithClasses() {
    return this.config.getBoolean("[@bundleSourcesWithClasses]", false);
  }

  public String getGroupId() {
    return this.config.getString("[@groupId]", null);
  }

  public String getArtifactId() {
    return this.config.getString("[@artifactId]", null);
  }

  public String getVersion() {
    return this.config.getString("[@version]", null);
  }

  public String getJavacSource() {
    return this.config.getString("[@javac-source]", "8");
  }

  public String getJavacTarget() {
    return this.config.getString("[@javac-target]", "8");
  }
  
  public List<File> getProjectSources() {
    return Collections.emptyList();
  }

  public List<File> getProjectTestSources() {
    return Collections.singletonList(getSourceDir());
  }

  public List<File> getProjectResourceDirectories() {
    return Collections.emptyList();
  }

  public List<File> getProjectTestResourceDirectories() {
    return Collections.singletonList(getResourcesDir());
  }

  /**
   * Whether to disable the compilation of the java sources (default: false).
   *
   * @return Whether to disable the compilation of the java sources (default: false).
   */
  public boolean isDisableCompile() {
    return this.config.getBoolean("[@disableCompile]", false);
  }

  public Set<String> getFacetIncludes() {
    List<Object> includes = this.config.getList("facets.include[@name]");
    Set<String> facetIncludes = new TreeSet<>();
    for (Object include : includes) {
      facetIncludes.add(String.valueOf(include));
    }
    return facetIncludes;
  }

  public Set<String> getFacetExcludes() {
    List<Object> excludes = this.config.getList("facets.exclude[@name]");
    Set<String> facetExcludes = new TreeSet<>();
    for (Object exclude : excludes) {
      facetExcludes.add(String.valueOf(exclude));
    }
    return facetExcludes;
  }

}
