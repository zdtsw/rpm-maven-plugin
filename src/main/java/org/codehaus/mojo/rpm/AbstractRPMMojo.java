package org.codehaus.mojo.rpm;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;
import org.codehaus.plexus.util.cli.CommandLineUtils.StringStreamConsumer;

/**
 * Abstract base class for building RPMs.
 * 
 * @author Carlos
 * @author Brett Okken, Cerner Corp.
 * @version $Id$
 */
abstract class AbstractRPMMojo extends AbstractMojo
{
    /**
     * Message for exception indicating that a {@link Source} has a {@link Source#getDestination() destination}, but
     * refers to a {@link File#isDirectory() directory}.
     */
    private static final String DESTINATION_DIRECTORY_ERROR_MSG =
        "Source has a destination [{0}], but the location [{1}] does not refer to a file.";
    
    /**
     * The key of the map is the directory where the files should be linked to. The value is the {@code List}
     * of {@link SoftlinkSource}s to be linked to.
     * @since 2.0-beta-3
     */
    private final Map linkTargetToSources = new LinkedHashMap();

    /**
     * The name portion of the output file name.
     * 
     * @parameter expression="${project.artifactId}"
     * @required
     */
    private String name;

    /**
     * The version portion of the RPM file name.
     * 
     * @parameter alias="version" expression="${project.version}"
     * @required
     */
    private String projversion;

    /**
     * The release portion of the RPM file name.
     * <p>
     * Beginning with 2.0-beta-2, this is an optional parameter. By default, the release will be generated from the
     * modifier portion of the <a href="#projversion">project version</a> using the following rules:
     * <ul>
     * <li>If no modifier exists, the release will be <code>1</code>.</li>
     * <li>If the modifier ends with <i>SNAPSHOT</i>, the timestamp (in UTC) of the build will be appended to end.</li>
     * <li>All instances of <code>'-'</code> in the modifier will be replaced with <code>'_'</code>.</li>
     * <li>If a modifier exists and does not end with <i>SNAPSHOT</i>, <code>"_1"</code> will be appended to end.</li>
     * </ul>
     * </p>
     * 
     * @parameter
     */
    private String release;

    /**
     * The target architecture for the rpm. The default value is <i>noarch</i>.
     * <p>
     * For passivity purposes, a value of <code>true</code> or <code>false</code> will indicate whether the <a
     * href="http://plexus.codehaus.org/plexus-utils/apidocs/org/codehaus/plexus/util/Os.html#OS_ARCH">architecture</a>
     * of the build machine will be used. Any other value (such as <tt>x86_64</tt>) will set the architecture of the
     * rpm to <tt>x86_64</tt>.
     * </p>
     * <p>
     * This can also be used in conjunction with <a href="source-params.html#targetArchitecture">Source
     * targetArchitecture</a> to flex the contents of the rpm based on the architecture.
     * </p>
     * 
     * @parameter
     */
    private String needarch;
    
    /**
     * The actual targeted architecture. This will be based on evaluation of {@link #needarch}.
     */
    private String targetArch;
    
    /**
     * The target os for building the RPM. By default, this will be populated to <a
     * href="http://plexus.codehaus.org/plexus-utils/apidocs/org/codehaus/plexus/util/Os.html#OS_NAME">Os.OS_NAME</a>.
     * <p>
     * This can be used in conjunction with <a href="source-params.html#targetOSName">Source targetOSName</a> to flex
     * the contents of the rpm based on operating system.
     * </p>
     * 
     * @parameter
     * @since 2.0-beta-3
     */
    private String targetOS;
    
    /**
     * The target vendor for building the RPM. By default, this will be populated to the result of <i>rpm -E
     * %{_host_vendor}</i>.
     * 
     * @parameter
     * @since 2.0-beta-3
     */
    private String targetVendor;

    /**
     * Set to a key name to sign the package using GPG. If <i>keyPassphrase</i> is not also provided, this will require
     * the input of the passphrase at the terminal.
     * 
     * @parameter expression="${gpg.keyname}"
     */
    private String keyname;

    /**
     * The passphrase for the <i>keyname</i> to sign the rpm. This utilizes <a href="http://expect.nist.gov/">expect</a>
     * and requires that {@code expect} be on the PATH.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Passphrase keyPassphrase;

    /**
     * The long description of the package.
     * 
     * @parameter expression="${project.description}"
     */
    private String description;

    /**
     * The one-line description of the package.
     * 
     * @parameter expression="${project.name}"
     */
    private String summary;

    /**
     * The one-line copyright information.
     * 
     * @parameter
     */
    private String copyright;

    /**
     * The distribution containing this package.
     * 
     * @parameter
     */
    private String distribution;

    /**
     * An icon for the package.
     * 
     * @parameter
     */
    private File icon;

    /**
     * The vendor supplying the package.
     * 
     * @parameter expression="${project.organization.name}"
     */
    private String vendor;

    /**
     * A URL for the vendor.
     * 
     * @parameter expression="${project.organization.url}"
     */
    private String url;

    /**
     * The package group for the package.
     * 
     * @parameter
     * @required
     */
    private String group;

    /**
     * The name of the person or group creating the package.
     * 
     * @parameter expression="${project.organization.name}"
     */
    private String packager;

    /**
     * Automatically add provided shared libraries.
     *
     * @parameter default-value="true"
     * @since 2.0-beta-4
     */
    private boolean autoProvides;
    
    /**
     * Automatically add requirements deduced from included shared libraries.
     *
     * @parameter default-value="true"
     * @since 2.0-beta-4
     */
    private boolean autoRequires;

    /**
     * The list of virtual packages provided by this package.
     * 
     * @parameter
     */
    private LinkedHashSet provides;

    /**
     * The list of requirements for this package.
     * 
     * @parameter
     */
    private LinkedHashSet requires;

    /**
     * The list of prerequisites for this package.
     * 
     * @since 2.0-beta-3
     * @parameter
     */
    private LinkedHashSet prereqs;
    
    /**
     * The list of obsoletes for this package.
     * 
     * @since 2.0-beta-3
     * @parameter
     */
    private LinkedHashSet obsoletes;

    /**
     * The list of conflicts for this package.
     * 
     * @parameter
     */
    private LinkedHashSet conflicts;

    /**
     * The relocation prefix for this package.
     * 
     * @parameter
     */
    private String prefix;

    /**
     * The area for RPM to use for building the package.
     * <p>
     * Beginning with release 2.0-beta-3, sub-directories will be created within the workarea for each execution of the
     * plugin within a life cycle.<br/>
     * 
     * The pattern will be <code>workarea/<i>name[-classifier]</i></code>.<br/>
     * 
     * The classifier portion is only applicable for the <a href="attached-rpm-mojo.html">attached-rpm</a> goal.
     * </p>
     * 
     * @parameter expression="${project.build.directory}/rpm"
     */
    private File workarea;

    /**
     * The list of file <a href="map-params.html">mappings</a>.
     * 
     * @parameter
     * @required
     */
    private List mappings;
    
    /**
     * The prepare script.
     * 
     * @parameter
     * @deprecated Use prepareScriplet
     */
    private String prepare;
    
    /**
     * The location of the prepare script. A File which does not exist is ignored.
     * 
     * @parameter
     * @deprecated Use prepareScriplet
     */
    private File prepareScript;
    
    /**
     * The prepare scriptlet;
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet prepareScriptlet;

    /**
     * The pre-installation script.
     * 
     * @parameter
     * @deprecated Use preinstallScriplet
     */
    private String preinstall;

    /**
     * The location of the pre-installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use preinstallScriplet
     */
    private File preinstallScript;
    
    /**
     * The pre-installation scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet preinstallScriptlet;

    /**
     * The post-installation script.
     * 
     * @parameter
     * @deprecated Use postinstallScriplet
     */
    private String postinstall;

    /**
     * The location of the post-installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use postinstallScriplet
     */
    private File postinstallScript;
    
    /**
     * The post install scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet postinstallScriptlet;

    /**
     * The installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use installScriplet
     */
    private String install;

    /**
     * The location of the installation script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use installScriplet
     */
    private File installScript;
    
    /**
     * The installation scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet installScriptlet;

    /**
     * The pre-removal script.
     * 
     * @parameter
     * @deprecated Use preremoveScriplet
     */
    private String preremove;

    /**
     * The location of the pre-removal script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use preremoveScriplet
     */
    private File preremoveScript;
    
    /**
     * The pre-removal scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet preremoveScriptlet;
    
    /**
     * The post-removal script.
     * 
     * @parameter
     * @deprecated Use postremoveScriplet
     */
    private String postremove;

    /**
     * The location of the post-removal script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use postremoveScriplet
     */
    private File postremoveScript;
    
    /**
     * The post-removal scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet postremoveScriptlet;

    /**
     * The verification script.
     * 
     * @parameter
     * @deprecated Use verifyScriplet
     */
    private String verify;

    /**
     * The location of the verification script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use verifyScriplet
     */
    private File verifyScript;
    
    /**
     * The verify scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet verifyScriptlet;

    /**
     * The clean script.
     * 
     * @parameter
     * @deprecated Use cleanScriplet
     */
    private String clean;

    /**
     * The location of the clean script.
     * <p>
     * Beginning with release 2.0-beta-3, a File which does not exist is ignored.
     * </p>
     * @parameter
     * @deprecated Use cleanScriplet
     */
    private File cleanScript;
    
    /**
     * The clean scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet cleanScriptlet;

    /**
     * The pretrans scriptlet.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet pretransScriptlet;

    /**
     * The posttrans script.
     * 
     * @parameter
     * @since 2.0-beta-4
     */
    private Scriptlet posttransScriptlet;
    
    /**
     * The list of triggers to take place on installation of other packages.
     * <pre>
     *  &lt;triggers>
     *      &lt;installTrigger>
     *          &lt;subpackage>optional&lt;/subpackage>
     *          &lt;program>program to execute (if not shell) optional&lt;/program>
     *          &lt;script>actual contents of script - optional&lt;/script>
     *          &lt;scriptFile>location of file containing script - optional&lt;/script>
     *          &lt;fileEncoding>character encoding for script file - recommended&lt;/fileEncoding>
     *          &lt;triggers>
     *              &lt;trigger>package/version to trigger on (i.e. jre > 1.5)&lt;/trigger>
     *              ...
     *          &lt;/triggers>
     *      &lt;/installTrigger>
     *      &lt;removeTrigger>
     *          ...
     *      &lt;/removeTrigger>
     *      &lt;postRemoveTrigger>
     *          ...
     *      &lt;/postRemoveTrigger>
     *      ...
     *  &lt;/triggers>
     * </pre>
     * @parameter
     * @since 2.0-beta-4
     * @see BaseTrigger
     */
    private List/* <Trigger> */triggers;

    /**
     * Filters (property files) to include during the interpolation of the pom.xml.
     * 
     * @parameter
     * @since 2.0
     */
    private List filters;

    /**
     * Expression preceded with the String won't be interpolated \${foo} will be replaced with ${foo}
     * 
     * @parameter expression="${maven.rpm.escapeString}"
     * @since 2.0
     */
    private String escapeString;

    /**
     * @parameter expression="${session}"
     * @readonly
     * @required
     * @since 2.0
     */
    private MavenSession session;

    /**
     * A Plexus component to copy files and directories. Using our own custom version of the DirectoryArchiver to allow
     * filtering of files.
     */
    private final FilteringDirectoryArchiver copier = new FilteringDirectoryArchiver();

    /**
     * @component role="org.apache.maven.shared.filtering.MavenFileFilter" roleHint="default"
     * @since 2.0
     */
    private MavenFileFilter mavenFileFilter;

    /**
     * The {@link FileUtils.FilterWrapper filter wrappers} to use for file filtering.
     * 
     * @since 2.0
     * @see #mavenFileFilter
     */
    private List/* FileUtils.FilterWrapper */defaultFilterWrappers;

    /**
     * The primary project artifact.
     * 
     * @parameter expression="${project.artifact}"
     * @required
     * @readonly
     */
    private Artifact artifact;

    /**
     * Auxillary project artifacts.
     * 
     * @parameter expression="${project.attachedArtifacts}
     * @required
     * @readonly
     */
    private List attachedArtifacts;

    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * A list of %define arguments
     * 
     * @parameter
     */
    private List defineStatements;

    /**
     * The default file mode (octal string) to assign to files when installed. <br/>
     * 
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>,
     * <a href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> 
     * are <b>NOT</b> populated.
     * 
     * @parameter
     * @since 2.0-beta-2
     */
    private String defaultFilemode;

    /**
     * The default directory mode (octal string) to assign to directories when installed.<br/>
     * 
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>,
     * <a href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> 
     * are <b>NOT</b> populated.
     * 
     * @parameter
     * @since 2.0-beta-2
     */
    private String defaultDirmode;

    /**
     * The default user name for files when installed.<br/>
     * 
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>,
     * <a href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> 
     * are <b>NOT</b> populated.
     * 
     * @parameter
     * @since 2.0-beta-2
     */
    private String defaultUsername;

    /**
     * The default group name for files when installed.<br/>
     * 
     * Only applicable to a <a href="map-params.html">Mapping</a> if <a href="map-params.html#filemode">filemode</a>,
     * <a href="map-params.html#username">username</a>, AND <a href="map-params.html#groupname">groupname</a> 
     * are <b>NOT</b> populated.
     * 
     * @parameter
     * @since 2.0-beta-2
     */
    private String defaultGroupname;
    
    /**
     * Indicates if the execution should be disabled. If <code>true</code>, nothing will occur during execution.
     * 
     * @parameter
     * @since 2.0
     */
    private boolean disabled;

    /** The root of the build area. */
    private File buildroot;

    /** The version string after parsing. */
    private String version;

    /** The changelog string. */
    private String changelog;

    /**
     * The changelog file. If the file does not exist, it is ignored.
     * 
     * @parameter
     * @since 2.0-beta-3
     */
    private File changelogFile;

    // // // Mojo methods

    /** {@inheritDoc} */
    public final void execute() throws MojoExecutionException, MojoFailureException
    {
        if ( disabled )
        {
            getLog().info( "MOJO is disabled. Doing nothing." );
            return;
        }

        // set up the maven file filter and FilteringDirectoryArchiver
        final MavenResourcesExecution mavenResourcesExecution = new MavenResourcesExecution();
        mavenResourcesExecution.setEscapeString( escapeString );

        try
        {
            defaultFilterWrappers =
                mavenFileFilter.getDefaultFilterWrappers( project, filters, false, this.session,
                                                          mavenResourcesExecution );
        }
        catch ( MavenFilteringException e )
        {
            getLog().error( "fail to build filering wrappers " + e.getMessage() );
            throw new MojoExecutionException( e.getMessage(), e );
        }

        copier.setMavenFileFilter( mavenFileFilter );

        checkParams();

        final String classifier = getClassifier();
        
        if ( classifier != null )
        {
            workarea = new File( workarea, name + '-' + classifier );
        }
        else
        {
            workarea = new File( workarea, name );
        }
        
        buildWorkArea();
        installFiles();
        writeSpecFile();
        buildPackage();
        
        afterExecution();
    }
    
    /**
     * Will be called on completion of {@link #execute()}. Provides subclasses an opportunity to
     * perform any post execution logic (such as attaching an artifact).
     * @throws MojoExecutionException If an error occurs.
     * @throws MojoFailureException If failure occurs.
     */
    protected void afterExecution() throws MojoExecutionException, MojoFailureException
    {
        
    }
    
    /**
     * Provides an opportunity for subclasses to provide an additional classifier for the rpm workarea.<br/> By default
     * this implementation returns {@code null}, which indicates that no additional classifier should be used.
     * 
     * @return An additional classifier to use for the rpm workarea or {@code null} if no additional classifier should
     * be used.
     */
    String getClassifier()
    {
        return null;
    }
    
    /**
     * Returns the generated rpm {@link File}.
     * @return The generated rpm <tt>File</tt>.
     */
    protected File getRPMFile()
    {
        File rpms = new File( workarea, "RPMS" );
        File archDir = new File( rpms, targetArch );
        
        return new File( archDir, name + '-' + version + '-' + release + '.' + targetArch + ".rpm" );
    }
    
    /**
     * Gets the default host vendor for system by executing <i>rpm -E %{_host_vendor}</i>.
     */
    private String getHostVendor() throws MojoExecutionException
    {
        Commandline cl = new Commandline();
        cl.setExecutable( "rpm" );
        cl.addArguments( new String[] { "-E", "%{_host_vendor}" } );

        StringStreamConsumer stdout = new StringStreamConsumer();
        StreamConsumer stderr = new LogStreamConsumer( LogStreamConsumer.WARN, getLog() );
        try
        {
            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( "About to execute \'" + cl.toString() + "\'" );
            }

            int result = CommandLineUtils.executeCommandLine( cl, stdout, stderr );
            if ( result != 0 )
            {
                throw new MojoExecutionException( "RPM query for default vendor returned: \'" + result
                    + "\' executing \'" + cl.toString() + "\'" );
            }
        }
        catch ( CommandLineException e )
        {
            throw new MojoExecutionException( "Unable to query for default vendor from RPM", e );
        }

        return stdout.getOutput().trim();
    }

    /**
     * Run the external command to build the package.
     * 
     * @throws MojoExecutionException if an error occurs
     */
    private void buildPackage() throws MojoExecutionException
    {
        File f = new File( workarea, "SPECS" );

        Commandline cl = new Commandline();
        cl.setExecutable( "rpmbuild" );
        cl.setWorkingDirectory( f.getAbsolutePath() );
        cl.createArg().setValue( "-bb" );
        cl.createArg().setValue( "--buildroot" );
        cl.createArg().setValue( buildroot.getAbsolutePath() );
        cl.createArg().setValue( "--define" );
        cl.createArg().setValue( "_topdir " + workarea.getAbsolutePath() );
        cl.createArg().setValue( "--target" );
        cl.createArg().setValue( targetArch + '-' + targetVendor + '-' + targetOS );
        
        //maintain passive behavior for keyPassphrase not being present
        if ( keyname != null && keyPassphrase == null )
        {
            cl.createArg().setValue( "--define" );
            cl.createArg().setValue( "_gpg_name " + keyname );
            cl.createArg().setValue( "--sign" );
        }
        
        cl.createArg().setValue( name + ".spec" );

        StreamConsumer stdout = new LogStreamConsumer( LogStreamConsumer.INFO, getLog() );
        StreamConsumer stderr = new LogStreamConsumer( LogStreamConsumer.WARN, getLog() );
        try
        {
            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( "About to execute \'" + cl.toString() + "\'" );
            }

            int result = CommandLineUtils.executeCommandLine( cl, stdout, stderr );
            if ( result != 0 )
            {
                throw new MojoExecutionException( "RPM build execution returned: \'" + result + "\' executing \'"
                    + cl.toString() + "\'" );
            }
        }
        catch ( CommandLineException e )
        {
            throw new MojoExecutionException( "Unable to build the RPM", e );
        }

        // now if the passphrase has been provided and we want to try and sign automatically
        if ( keyname != null && keyPassphrase != null )
        {
            RPMSigner signer = new RPMSigner( keyname, keyPassphrase.getPassphrase(), getLog() );

            try
            {
                signer.sign( getRPMFile() );
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Unable to sign RPM", e );
            }
        }
    }

    /**
     * Build the structure of the work area.
     * 
     * @throws MojoFailureException if a directory cannot be built
     * @throws MojoExecutionException if buildroot cannot be cleared (if exists)
     */
    private void buildWorkArea() throws MojoFailureException, MojoExecutionException
    {
        final String[] topdirs = { "BUILD", "RPMS", "SOURCES", "SPECS", "SRPMS", "buildroot" };

        // Build the top directory
        if ( !workarea.exists() )
        {
            getLog().info( "Creating directory " + workarea.getAbsolutePath() );
            if ( !workarea.mkdirs() )
            {
                throw new MojoFailureException( "Unable to create directory " + workarea.getAbsolutePath() );
            }
        }

        // Build each directory in the top directory
        for ( int i = 0; i < topdirs.length; i++ )
        {
            File d = new File( workarea, topdirs[i] );
            if ( d.exists() )
            {
                getLog().info( "Directory " + d.getAbsolutePath() + "already exists. Deleting all contents." );
                
                try
                {
                    FileUtils.cleanDirectory( d );
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "Unable to clear directory: " + d.getName(), e );
                }
            }
            else
            {
                getLog().info( "Creating directory " + d.getAbsolutePath() );
                if ( !d.mkdir() )
                {
                    throw new MojoFailureException( "Unable to create directory " + d.getAbsolutePath() );
                }
            }
        }

        // set build root variable
        buildroot = new File( workarea, "buildroot" );
    }

    /**
     * Check the parameters for validity.
     * 
     * @throws MojoFailureException if an invalid parameter is found
     * @throws MojoExecutionException if an error occurs reading a script
     */
    private void checkParams() throws MojoExecutionException, MojoFailureException
    {
        Log log = getLog();
        log.debug( "project version = " + projversion );

        // Check the version string
        int modifierIndex = projversion.indexOf( '-' );
        if ( modifierIndex == -1 )
        {
            version = projversion;
            if ( release == null || release.length() == 0 )
            {
                release = "1";

                log.debug( "Release set to: 1" );
            }
        }
        else
        {
            version = projversion.substring( 0, modifierIndex );
            log.warn( "Version string truncated to " + version );

            if ( release == null || release.length() == 0 )
            {
                String modifier = projversion.substring( modifierIndex + 1, projversion.length() );
                log.debug( "version modifier = " + modifier );

                modifier = modifier.replace( '-', '_' );

                if ( modifier.endsWith( "SNAPSHOT" ) )
                {
                    SimpleDateFormat simpleDateFormat = new SimpleDateFormat( "yyyyMMddHHmmss" );
                    simpleDateFormat.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
                    modifier += simpleDateFormat.format( new Date() );
                }
                else
                {
                    modifier += "_1";
                }

                release = modifier;

                log.debug( "Release set to: " + modifier );
            }
        }

        // evaluate needarch and populate targetArch
        if ( needarch == null || needarch.length() == 0 || "false".equalsIgnoreCase( needarch ) )
        {
            targetArch = "noarch";
        }
        else if ( "true".equalsIgnoreCase( needarch ) )
        {
            targetArch = Os.OS_ARCH;
        }
        else
        {
            targetArch = needarch;
        }
        log.debug( "targetArch = " + targetArch );

        // provide default targetOS if value not given
        if ( targetOS == null || targetOS.length() == 0 )
        {
            targetOS = Os.OS_NAME;
        }
        log.debug( "targetOS = " + targetOS );

        if ( targetVendor == null || targetVendor.length() == 0 )
        {
            targetVendor = getHostVendor();
        }
        log.debug( "targetVendor = " + targetVendor );

        // Various checks in the mappings
        for ( Iterator it = mappings.iterator(); it.hasNext(); )
        {
            Mapping map = (Mapping) it.next();
            if ( map.getDirectory() == null )
            {
                throw new MojoFailureException( "<mapping> element must contain the destination directory" );
            }
            if ( map.getSources() != null )
            {
                for ( Iterator sit = map.getSources().iterator(); sit.hasNext(); )
                {
                    Source src = (Source) sit.next();
                    if ( src.getLocation() == null )
                    {
                        throw new MojoFailureException( "<mapping><source> tag must contain the source directory" );
                    }
                }
            }
        }

        prepareScriptlet = passiveScripts( "prepare", prepareScriptlet, prepare, prepareScript );
        preinstallScriptlet = passiveScripts( "preinstall", preinstallScriptlet, preinstall, preinstallScript );
        installScriptlet = passiveScripts( "install", installScriptlet, install, installScript );
        postinstallScriptlet = passiveScripts( "postinstall", postinstallScriptlet, postinstall, postinstallScript );
        preremoveScriptlet = passiveScripts( "preremove", preremoveScriptlet, preremove, preremoveScript );
        postremoveScriptlet = passiveScripts( "postremove", postremoveScriptlet, postremove, postremoveScript );
        verifyScriptlet = passiveScripts( "verify", verifyScriptlet, verify, verifyScript );
        cleanScriptlet = passiveScripts( "clean", cleanScriptlet, clean, cleanScript );

        if ( ( changelog == null ) && ( changelogFile != null ) )
        {
            if ( !changelogFile.exists() )
            {
                log.debug( changelogFile.getAbsolutePath() + " does not exist - ignoring" );
            }
            else
            {
                try
                {
                    StringBuffer sb = new StringBuffer();
                    BufferedReader br = new BufferedReader( new FileReader( changelogFile ) );
                    while ( br.ready() )
                    {
                        String line = br.readLine();
                        sb.append( line );
                        sb.append( '\n' );
                    }
                    br.close();
                    changelog = sb.toString();
                }
                catch ( Throwable t )
                {
                    throw new MojoExecutionException( "Unable to read " + changelogFile.getAbsolutePath(), t );
                }
            }
        }

        //generate copyright text if not set
        if ( copyright == null )
        {
            copyright = generateCopyrightText();
        }
        
        // if this package obsoletes any packages, make sure those packages are added to the provides list
        if ( obsoletes != null )
        {
            if ( provides == null )
            {
                provides = obsoletes;
            }
            else
            {
                provides.addAll( obsoletes );
            }
        }
    }

    /**
     * Handles the <i>scriptlet</i> and corresponding deprecated <i>script</i> and <i>file</i>. Will return a
     * {@link Scriptlet} representing the coalesced stated.
     */
    private final Scriptlet passiveScripts( final String name, Scriptlet scriptlet, final String script, 
                                            final File file )
    {
        if ( scriptlet == null && ( script != null || file != null ) )
        {
            scriptlet = new Scriptlet();
            scriptlet.setScript( script );
            scriptlet.setScriptFile( file );
            getLog().warn( "Deprecated <" + name + "> and/or <" + name + "Script> used - should use <" + name 
                           + "Scriptlet>" );
        }

        return scriptlet;
    }

    /**
     * Copy an artifact.
     * 
     * @param art The artifact to copy
     * @param dest The destination directory
     * @param stripVersion Whether or not to strip the artifact version from the filename
     * @return Artifact file name
     * @throws MojoExecutionException if a problem occurs
     */
    private String copyArtifact( Artifact art, File dest, boolean stripVersion ) throws MojoExecutionException
    {
        if ( art.getFile() == null )
        {
            getLog().warn( "Artifact " + art + " requested in configuration." );
            getLog().warn( "Plugin must run in standard lifecycle for this to work." );
            return null;
        }

        String outputFileName;
        if ( stripVersion )
        {
            final String classifier = art.getClassifier();
            // strip the version from the file name
            outputFileName = art.getArtifactId();
            if ( classifier != null )
            {
                outputFileName += '-';
                outputFileName += classifier;
            }
            outputFileName += '.';
            outputFileName += art.getType();
        }
        else
        {
            outputFileName = art.getFile().getName();
        }

        copySource( art.getFile(), outputFileName, dest, null, null, false );
        return outputFileName;
    }

    /**
     * Copy a set of files.
     * 
     * @param src The file or directory to start copying from
     * @param srcName The src file name to be used in the copy, only used if the src is not a directory.
     * @param dest The destination directory
     * @param incl The list of inclusions
     * @param excl The list of exclusions
     * @param filter Indicates if the file(s) being copied should be filtered.
     * @return List of file names, relative to <i>dest</i>, copied to <i>dest</i>.
     * @throws MojoExecutionException if a problem occurs
     */
    private List copySource( File src, String srcName, File dest, List incl, List excl, boolean filter )
        throws MojoExecutionException
    {
        try
        {
            // Set the destination
            copier.setDestFile( dest );

            // Set the source
            if ( src.isDirectory() )
            {
                String[] ia = null;
                if ( incl != null )
                {
                    ia = (String[]) incl.toArray( new String[0] );
                }

                String[] ea = null;
                if ( excl != null )
                {
                    ea = (String[]) excl.toArray( new String[0] );
                }

                copier.addDirectory( src, "", ia, ea );
            }
            else
            {
                // set srcName to default if null
                srcName = srcName != null ? srcName : src.getName();
                copier.addFile( src, srcName );
            }

            copier.setFilter( filter );
            copier.setFilterWrappers( defaultFilterWrappers );

            // Perform the copy
            copier.createArchive();

            Map copiedFilesMap = copier.getFiles();
            List copiedFiles = new ArrayList( copiedFilesMap.size() );
            for ( Iterator i = copiedFilesMap.keySet().iterator(); i.hasNext(); )
            {
                String key = (String) i.next();
                if ( key != null && key.length() > 0 )
                {
                    copiedFiles.add( key );
                }
            }

            // Clear the list for the next mapping
            copier.resetArchiver();

            return copiedFiles;
        }
        catch ( Throwable t )
        {
            throw new MojoExecutionException( "Unable to copy files for packaging: " + t.getMessage(), t );
        }
    }

    /**
     * Determine if the dependency matches an include or exclude list.
     * 
     * @param dep The dependency to check
     * @param list The list to check against
     * @return <code>true</code> if the dependency was found on the list
     */
    private boolean depMatcher( Artifact dep, List list )
    {
        if ( list == null )
        {
            // No list, not possible to match
            return false;
        }

        for ( Iterator it = list.iterator(); it.hasNext(); )
        {
            final Artifact item = (Artifact) it.next();
            getLog().debug( "Compare " + dep + " to " + item );
            final String groupId = item.getGroupId();
            if ( StringUtils.isEmpty( groupId ) || "*".equals( groupId ) || groupId.equals( dep.getGroupId() ) )
            {
                getLog().debug( "... Group matches" );
                final String artifactId = item.getArtifactId();
                if ( StringUtils.isEmpty( artifactId ) || "*".equals( artifactId )
                    || artifactId.equals( dep.getArtifactId() ) )
                {
                    getLog().debug( "... Artifact matches" );
                    // ArtifactVersion av = item.getVersionRange().matchVersion( dep.getAvailableVersions() );
                    try
                    {
                        if ( item.getVersionRange().containsVersion( dep.getSelectedVersion() ) )
                        {
                            getLog().debug( "... Version matches" );
                            return true;
                        }
                    }
                    catch ( OverConstrainedVersionException ocve )
                    {
                        getLog().debug( "... caught OverConstrainedVersionException" );
                    }
                }
            }
        }

        // Not found
        return false;
    }

    /**
     * Copy the files from the various mapping sources into the build root.
     * 
     * @throws MojoExecutionException if a problem occurs
     * @throws MojoFailureException 
     */
    private void installFiles() throws MojoExecutionException, MojoFailureException
    {
        // Copy icon, if specified
        if ( icon != null )
        {
            File icondest = new File( workarea, "SOURCES" );
            copySource( icon, null, icondest, null, null, false );
        }

        // Process each mapping
        for ( Iterator it = mappings.iterator(); it.hasNext(); )
        {
            Mapping map = (Mapping) it.next();
            File dest = new File( buildroot + map.getDestination() );

            if ( map.isDirOnly() )
            {
                // Build the output directory if it doesn't exist
                if ( !dest.exists() )
                {
                    getLog().info( "Creating empty directory " + dest.getAbsolutePath() );
                    if ( !dest.mkdirs() )
                    {
                        throw new MojoExecutionException( "Unable to create " + dest.getAbsolutePath() );
                    }
                }
            }
            else
            {
                processSources( map, dest );

                ArtifactMap art = map.getArtifact();
                if ( art != null )
                {
                    List artlist = selectArtifacts( art );
                    for ( Iterator ait = artlist.iterator(); ait.hasNext(); )
                    {
                        Artifact artifactInstance = (Artifact) ait.next();
                        copyArtifact( artifactInstance, dest, false );
                        map.addCopiedFileNameRelativeToDestination( artifactInstance.getFile().getName() );
                    }
                }

                Dependency dep = map.getDependency();
                if ( dep != null )
                {
                    List deplist = selectDependencies( dep );
                    for ( Iterator dit = deplist.iterator(); dit.hasNext(); )
                    {
                        Artifact artifactInstance = (Artifact) dit.next();
                        // pass in dependency stripVersion parameter
                        String outputFileName = copyArtifact( artifactInstance, dest, dep.getStripVersion() );
                        map.addCopiedFileNameRelativeToDestination( outputFileName );
                    }
                }
                
                if ( map.getCopiedFileNamesRelativeToDestination().isEmpty() )
                {
                    getLog().info( "Mapping empty with destination: " + dest.getName() );
                    // Build the output directory if it doesn't exist
                    if ( !dest.exists() )
                    {
                        getLog().info( "Creating empty directory " + dest.getAbsolutePath() );
                        if ( !dest.mkdirs() )
                        {
                            throw new MojoExecutionException( "Unable to create " + dest.getAbsolutePath() );
                        }
                    }
                }
            }
        }
    }

    /**
     * Installs the {@link Mapping#getSources() sources} to <i>dest</i>
     * @param map The <tt>Mapping</tt> to process the {@link Source sources} for.
     * @param dest The destination directory for the sources.
     * @throws MojoExecutionException
     * @throws MojoFailureException 
     */
    private void processSources( Mapping map, File dest ) throws MojoExecutionException, MojoFailureException
    {
        if ( !dest.exists() )
        {
            if ( !dest.mkdirs() )
            {
                throw new MojoExecutionException( "unable to create directory: " + dest.getAbsolutePath() );
            }
        }
        
        String relativeDestination = map.getDestination();
        if ( !relativeDestination.endsWith( File.separator ) )
        {
            relativeDestination += File.separatorChar;
        }
        
        List srcs = map.getSources();
        if ( srcs != null )
        {
            // for passivity, we will always use lowercase representation of architecture
            // for comparison purposes.
            final String targetArchComparison = targetArch.toLowerCase( Locale.ENGLISH );
            
            // it is important that for each Source we set the files that are "installed".
            for ( Iterator sit = srcs.iterator(); sit.hasNext(); )
            {
                Source src = (Source) sit.next();
                
                if ( !src.matchesArchitecture( targetArchComparison ) )
                {
                    getLog().debug( "Source does not match target architecture: " + src.toString() );
                    continue;
                }
                
                if ( !src.matchesOSName( targetOS ) )
                {
                    getLog().debug( "Source does not match target os name: " + src.toString() );
                    continue;
                }
                
                File location = src.getLocation();
                //it is important that we check if softlink source first as the "location" may
                //exist in the filesystem of the build machine
                if ( src instanceof SoftlinkSource ) 
                {
                    List sources = (List) linkTargetToSources.get( relativeDestination );
                    if ( sources == null )
                    {
                        sources = new LinkedList();
                        linkTargetToSources.put( relativeDestination, sources );
                    }
                    
                    sources.add( src );
                    
                    ( ( SoftlinkSource ) src ).setSourceMapping( map );
                    map.setHasSoftLinks( true );
                }
                else if ( location.exists() )
                {
                    final String destination = src.getDestination();
                    if ( destination == null )
                    {
                        List elist = src.getExcludes();
                        if ( !src.getNoDefaultExcludes() )
                        {
                            if ( elist == null )
                            {
                                elist = new ArrayList();
                            }
                            elist.addAll( FileUtils.getDefaultExcludesAsList() );
                        }
                        map.addCopiedFileNamesRelativeToDestination( 
                            copySource( src.getLocation(), null, dest, src.getIncludes(), elist, src.isFilter() ) );
                    }
                    else
                    {
                        if ( !location.isFile() )
                        {
                            throw new MojoExecutionException(
                                MessageFormat.format(
                                    DESTINATION_DIRECTORY_ERROR_MSG,
                                    new Object[] { destination, location.getName() } ) );
                        }

                        copySource( location, destination, dest, Collections.EMPTY_LIST, Collections.EMPTY_LIST,
                                    src.isFilter() );

                        map.addCopiedFileNameRelativeToDestination( destination );
                    }
                }
                else
                {
                    throw new MojoExecutionException( "Source location " + location + " does not exist" );
                }
            }
        }
    }

    /**
     * Make a list of the artifacts to package in this mapping.
     * 
     * @param am The artifact mapping information
     * @return The list of artifacts to package
     */
    private List selectArtifacts( ArtifactMap am )
    {
        List retval = new ArrayList();
        List clist = am.getClassifiers();

        if ( clist == null )
        {
            retval.add( artifact );
            retval.addAll( attachedArtifacts );
        }
        else
        {
            if ( clist.contains( null ) )
            {
                retval.add( artifact );
            }
            for ( Iterator ait = attachedArtifacts.iterator(); ait.hasNext(); )
            {
                Artifact aa = (Artifact) ait.next();
                if ( ( aa.hasClassifier() ) && ( clist.contains( aa.getClassifier() ) ) )
                {
                    retval.add( aa );
                }
            }
        }

        return retval;
    }

    /**
     * Make a list of the dependencies to package in this mapping.
     * 
     * @param d The artifact mapping information
     * @return The list of artifacts to package
     */
    private List selectDependencies( Dependency d )
    {
        List retval = new ArrayList();
        List inc = d.getIncludes();
        List exc = d.getExcludes();

        Collection deps = project.getArtifacts();
        if ( deps == null || deps.isEmpty() )
        {
            return retval;
        }

        for ( Iterator it = deps.iterator(); it.hasNext(); )
        {
            Artifact pdep = (Artifact) it.next();
            getLog().debug( "Dependency is " + pdep + " at " + pdep.getFile() );
            if ( !depMatcher( pdep, exc ) )
            {
                getLog().debug( "--> not excluded" );
                if ( ( inc == null ) || ( depMatcher( pdep, inc ) ) )
                {
                    getLog().debug( "--> included" );
                    retval.add( pdep );
                }
            }
        }

        return retval;
    }

    /**
     * Write the SPEC file.
     * 
     * @throws MojoExecutionException if an error occurs writing the file
     */
    private void writeSpecFile() throws MojoExecutionException
    {
        File f = new File( workarea, "SPECS" );
        File specf = new File( f, name + ".spec" );

        try
        {
            getLog().info( "Creating spec file " + specf.getAbsolutePath() );
            PrintWriter spec = new PrintWriter( new FileWriter( specf ) );
            try
            {
                new SpecWriter( this, spec ).writeSpecFile();
            }
            finally
            {
                spec.close();
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Unable to write " + specf.getAbsolutePath(), e );
        }
    }

    /**
     * Generates the copyright text from {@link MavenProject#getOrganization()} and
     * {@link MavenProject#getInceptionYear()}.
     * 
     * @return Generated copyright text from the organization name and inception year.
     */
    private String generateCopyrightText()
    {
        String copyrightText;
        String year = project.getInceptionYear();
        String organization = project.getOrganization() == null ? null : project.getOrganization().getName();
        if ( ( year != null ) && ( organization != null ) )
        {
            copyrightText = year + " " + organization;
        }
        else
        {
            copyrightText = year == null ? organization : year;
        }
        return copyrightText;
    }

    /**
     * @return Returns the {@link #linkTargetToSources}.
     */
    final Map getLinkTargetToSources()
    {
        return this.linkTargetToSources;
    }

    /**
     * @return Returns the {@link #name}.
     */
    final String getName()
    {
        return this.name;
    }

    /**
     * @return Returns the {@link #release}.
     */
    final String getRelease()
    {
        return this.release;
    }

    /**
     * @return Returns the {@link #description}.
     */
    final String getDescription()
    {
        return this.description;
    }

    /**
     * @return Returns the {@link #summary}.
     */
    final String getSummary()
    {
        return this.summary;
    }

    /**
     * @return Returns the {@link #copyright}.
     */
    final  String getCopyright()
    {
        return this.copyright;
    }

    /**
     * @return Returns the {@link #distribution}.
     */
    final String getDistribution()
    {
        return this.distribution;
    }

    /**
     * @return Returns the {@link #icon}.
     */
    final File getIcon()
    {
        return this.icon;
    }

    /**
     * @return Returns the {@link #vendor}.
     */
    final String getVendor()
    {
        return this.vendor;
    }

    /**
     * @return Returns the {@link #url}.
     */
    final String getUrl()
    {
        return this.url;
    }

    /**
     * @return Returns the {@link #group}.
     */
    final String getGroup()
    {
        return this.group;
    }

    /**
     * @return Returns the {@link #packager}.
     */
    final String getPackager()
    {
        return this.packager;
    }

    /**
     * @return Returns the {@link #autoProvides}.
     */
    final boolean isAutoProvides()
    {
        return this.autoProvides;
    }

    /**
     * @return Returns the {@link #autoRequires}.
     */
    final boolean isAutoRequires()
    {
        return this.autoRequires;
    }

    /**
     * @return Returns the {@link #provides}.
     */
    final LinkedHashSet getProvides()
    {
        return this.provides;
    }

    /**
     * @return Returns the {@link #requires}.
     */
    final LinkedHashSet getRequires()
    {
        return this.requires;
    }

    /**
     * @return Returns the {@link #prereqs}.
     */
    final LinkedHashSet getPrereqs()
    {
        return this.prereqs;
    }

    /**
     * @return Returns the {@link #obsoletes}.
     */
    final LinkedHashSet getObsoletes()
    {
        return this.obsoletes;
    }

    /**
     * @return Returns the {@link #conflicts}.
     */
    final LinkedHashSet getConflicts()
    {
        return this.conflicts;
    }

    /**
     * @return Returns the {@link #prefix}.
     */
    final String getPrefix()
    {
        return this.prefix;
    }

    /**
     * @return Returns the {@link #mappings}.
     */
    final List getMappings()
    {
        return this.mappings;
    }

    /**
     * @return Returns the {@link #prepareScriptlet}.
     */
    final Scriptlet getPrepareScriptlet()
    {
        return this.prepareScriptlet;
    }

    /**
     * @return Returns the {@link #preinstallScriptlet}.
     */
    final Scriptlet getPreinstallScriptlet()
    {
        return this.preinstallScriptlet;
    }

    /**
     * @return Returns the {@link #postinstallScriptlet}.
     */
    final Scriptlet getPostinstallScriptlet()
    {
        return this.postinstallScriptlet;
    }

    /**
     * @return Returns the {@link #installScriptlet}.
     */
    final Scriptlet getInstallScriptlet()
    {
        return this.installScriptlet;
    }

    /**
     * @return Returns the {@link #preremoveScriptlet}.
     */
    final Scriptlet getPreremoveScriptlet()
    {
        return this.preremoveScriptlet;
    }

    /**
     * @return Returns the {@link #postremoveScriptlet}.
     */
    final  Scriptlet getPostremoveScriptlet()
    {
        return this.postremoveScriptlet;
    }

    /**
     * @return Returns the {@link #verifyScriptlet}.
     */
    final Scriptlet getVerifyScriptlet()
    {
        return this.verifyScriptlet;
    }

    /**
     * @return Returns the {@link #cleanScriptlet}.
     */
    final Scriptlet getCleanScriptlet()
    {
        return this.cleanScriptlet;
    }

    /**
     * @return Returns the {@link #pretransScriptlet}.
     */
    final Scriptlet getPretransScriptlet()
    {
        return this.pretransScriptlet;
    }

    /**
     * @return Returns the {@link #posttransScriptlet}.
     */
    final Scriptlet getPosttransScriptlet()
    {
        return this.posttransScriptlet;
    }

    /**
     * @return Returns the {@link #triggers}.
     */
    final List getTriggers()
    {
        return this.triggers;
    }

    /**
     * @return Returns the {@link #defineStatements}.
     */
    final List getDefineStatements()
    {
        return this.defineStatements;
    }

    /**
     * @return Returns the {@link #defaultFilemode}.
     */
    final String getDefaultFilemode()
    {
        return this.defaultFilemode;
    }

    /**
     * @return Returns the {@link #defaultDirmode}.
     */
    final String getDefaultDirmode()
    {
        return this.defaultDirmode;
    }

    /**
     * @return Returns the {@link #defaultUsername}.
     */
    final String getDefaultUsername()
    {
        return this.defaultUsername;
    }

    /**
     * @return Returns the {@link #defaultGroupname}.
     */
    final String getDefaultGroupname()
    {
        return this.defaultGroupname;
    }

    /**
     * @return Returns the {@link #buildroot}.
     */
    final File getBuildroot()
    {
        return this.buildroot;
    }

    /**
     * @return Returns the {@link #version}.
     */
    final String getVersion()
    {
        return this.version;
    }

    /**
     * @return Returns the {@link #changelog}.
     */
    final String getChangelog()
    {
        return this.changelog;
    }
}