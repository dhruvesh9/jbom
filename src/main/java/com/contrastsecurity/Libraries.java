package com.contrastsecurity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import com.github.packageurl.PackageURL;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.Component.Scope;

public class Libraries {

    private static Set<Component> invoked = new HashSet<Component>();
    private static Set<String> codesourceExamined = new HashSet<String>();
    private static Set<Component> libraries = new HashSet<Component>();

    public static void main( String[] args ) throws Exception {
        String url1 = "jar:file:/Users/jeffwilliams/Downloads/log4j%20demo/myproject-0.0.1-SNAPSHOT.jar!/BOOT-INF/lib/log4j-api-2.14.1.jar!/";
        String url2 = "jar:file:/Users/jeffwilliams/Downloads/log4j%20demo/myproject-0.0.1-SNAPSHOT.jar!/BOOT-INF/lib/log4j-core-2.14.1.jar!/";

        Libraries.addAllLibraries( url1 );
        Libraries.addAllLibraries( url2 );
        dump();
        CycloneDXModel sbom = new CycloneDXModel();
		sbom.setComponents( Libraries.getLibraries() );		

		sbom.save( "sbom.json" );
    }

    // find containing jar file and include ALL libraries
    public static void addAllLibraries( String codesource ) {

        if ( codesourceExamined.contains( codesource ) ) {
            return;
        }
        codesourceExamined.add( codesource );

        if ( !isArchive( codesource ) ) {
            return;
        }

        System.out.println( "SCANNING: " + codesource );

        try {
            // save this lib
            String decoded = URLDecoder.decode( codesource, "UTF-8" );
            String filepath = decoded.substring( decoded.lastIndexOf(":") + 1);
            String parts[] = filepath.split( "!/" );
            String path = parts[0];
            File f = new File( path );
            Library lib = new Library( parts[parts.length-1] );  // last segment
            lib.parsePath( path );
            lib.setType( Library.Type.LIBRARY );

            // add Contrast custom properties
            lib.addProperty("source", "Contrast Security - https://contrastsecurity.com");
            lib.addProperty("tool", "jbom - https://github.com/Contrast-Security-OSS/jbom");
            lib.setScope( Scope.REQUIRED );
            lib.addProperty( "codesource", codesource );

            libraries.add( lib );
            invoked.add( lib );

            // then add any libraries inside
            JarInputStream jis1 = new JarInputStream( new FileInputStream( f ) );
            String sha1 = hash( jis1, MessageDigest.getInstance("SHA1") );
            lib.addHash( new Hash( Hash.Algorithm.SHA1, sha1 ) );
            
            JarInputStream jis2 = new JarInputStream( new FileInputStream( f ) );
            String md5 = hash( jis2, MessageDigest.getInstance("MD5") );
            lib.addHash( new Hash( Hash.Algorithm.MD5, md5 ) );

            lib.addProperty( "maven", "https://search.maven.org/search?q=1:" + sha1 );

            // scan for nested libraries
            JarInputStream jis3 = new JarInputStream( new FileInputStream( f ) );
            JarFile jarfile = new JarFile( f );
            scan( jarfile, jis3, codesource );
        } catch( Exception e ) {
            Logger.log( "The safelog4j project needs your help to deal with unusual CodeSources." );
            Logger.log( "Report issue here: https://github.com/Contrast-Security-OSS/safelog4j/issues/new/choose" );
            Logger.log( "Please include:" );
            Logger.log( "  CodeSource: " + codesource );
            e.printStackTrace();
        }
    }

    public static void scan( JarFile jarFile, JarInputStream jis, String codesource ) throws Exception {
        JarEntry entry = null;
        while ((entry = jis.getNextJarEntry()) != null) {
            String nestedName = entry.getName();
            try {
                if ( isArchive( nestedName) ) {

                    Library innerlib = new Library();
                    innerlib.setScope( Scope.REQUIRED );
                    innerlib.parsePath( nestedName );
                    innerlib.addProperty( "codesource", codesource );

                    libraries.add( innerlib );
                    innerlib.setType( Library.Type.LIBRARY );

                    InputStream nis1 = jarFile.getInputStream( entry );
                    String md5 = hash( nis1, MessageDigest.getInstance("MD5") );
                    innerlib.addHash( new Hash( Hash.Algorithm.MD5, md5 ) );

                    InputStream nis2 = jarFile.getInputStream( entry );
                    String sha1 = hash( nis2, MessageDigest.getInstance("SHA1") );
                    innerlib.addHash( new Hash( Hash.Algorithm.SHA1, sha1 ) );

                    innerlib.addProperty( "maven", "https://search.maven.org/search?q=1:" + sha1 );

                    InputStream nis3 = jarFile.getInputStream( entry );
                    JarInputStream innerJis = new JarInputStream( nis3 );

                    Manifest mf = innerJis.getManifest();
                    if ( mf != null ) {
                        Attributes attr = mf.getMainAttributes();
                        String group = attr.getValue( "Implementation-Vendor-Id" );
                        String artifact = attr.getValue( "Implementation-Title" );
                        if ( group != null ) innerlib.setGroup(group);
                        if ( artifact != null ) innerlib.setName(artifact);
                    }

                    // scan through this jar to find any pom files
                    InputStream nis4 = jarFile.getInputStream( entry );
                    JarInputStream innerJis4 = new JarInputStream( nis4 );
                    while ((entry = innerJis4.getNextJarEntry()) != null) {
                        if ( entry.getName().endsWith( "/pom.xml" ) ) {
                            try {
                                parsePom( innerJis4, innerlib );
                            } catch( Exception e ) {
                                // Logger.log( "Problem parsing POM from " + nestedName + " based on " + codesource + ". Continuing." );
                            }
                        }
                    }
                    
                    try {
                        if ( innerlib.getGroup() != null && innerlib.getName() != null ) {
                            innerlib.setPurl(new PackageURL( PackageURL.StandardTypes.MAVEN, innerlib.getGroup(), innerlib.getName(), innerlib.getVersion(), null, null));
                        }
                    } catch( Exception e ) {
                        // continue
                    }
                }
            } catch( Exception e ) {
                Logger.log( "Problem extracting metadata from " + nestedName + " based on " + codesource + ". Continuing." );
                e.printStackTrace();
            }
        }
    }

    public static boolean isArchive( String filename ) {
        if ( filename.endsWith( "!/" ) ) {
            filename = filename.substring( 0, filename.length()-2 );
        }
        boolean isArchive = 
            filename.endsWith( ".jar" ) 
            || filename.endsWith( "war" )
            || filename.endsWith( "ear" )
            || filename.endsWith( "zip" );
        return isArchive;
    }

    private static void parsePom(JarInputStream is, Library lib) throws Exception {
        // String pom = getPOM( is );
        // System.out.println( pom );
        
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(is);
        // Model model = reader.read(new StringReader( pom ));
        String g = model.getGroupId();
        String a = model.getArtifactId();
        String v = model.getVersion();
        if ( g != null ) lib.setGroup( g );
        if ( a != null ) lib.setName( a );
        if ( v != null ) lib.setVersion( v );
    }

    public static List<Component> getLibraries() {
        return new ArrayList<Component>(libraries);
    }

    public static void dump() {
        Logger.log( "Found " + getLibraries().size() + " libraries" );
        for ( Component lib : getLibraries() ) {
            Logger.log( lib.toString() );
        }
    }

    public static String getPOM( InputStream is ) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int len;
        byte[] buf = new byte[8192];    
        while ((len = is.read(buf, 0, buf.length)) != -1) {
            baos.write(buf, 0, len);
        }
        return new String( baos.toByteArray(), "UTF-8" );
    }

    // streaming hash, low memory use
    public static String hash( InputStream is, MessageDigest md ) throws Exception {
        DigestInputStream dis = new DigestInputStream(is, md);
        byte[] buf = new byte[8192];
        for (int len; (len = dis.read(buf)) != -1;) {
        }
        return toHexString(md.digest());
    }

    public static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    public static String toHexString(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
    