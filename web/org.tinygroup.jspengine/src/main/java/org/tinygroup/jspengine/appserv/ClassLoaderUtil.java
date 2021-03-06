/**
 *  Copyright (c) 1997-2013, www.tinygroup.org (luo_guo@icloud.com).
 *
 *  Licensed under the GPL, Version 3.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.gnu.org/licenses/gpl.html
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.tinygroup.jspengine.appserv;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;
import java.util.Vector;
import java.util.jar.JarFile;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.MessageFormat;

import org.tinygroup.jspengine.common.util.logging.LogDomains;

import sun.misc.URLClassPath;


/**
 * Provides utility functions related to URLClassLoaders or subclasses of it.
 *
 *                  W  A  R  N  I  N  G    
 *
 *This class uses undocumented, unpublished, private data structures inside
 *java.net.URLClassLoader and sun.misc.URLClassPath.  Use with extreme caution.
 *
 * @author tjquinn
 */
public class ClassLoaderUtil {

    /** records whether initialization has been completed */
    private static boolean isInitialized = false;
    
    /** names of classes and fields of interest for closing the loader's jar files */
    private static final String URLCLASSLOADER_UCP_FIELD_NAME = "ucp";
    
    private static final String URLCLASSPATH_LOADERS_FIELD_NAME = "loaders"; // ArrayList of URLClassPath.Loader 
    private static final String URLCLASSPATH_URLS_FIELD_NAME = "urls"; // Stack of URL
    private static final String URLCLASSPATH_LMAP_FIELD_NAME = "lmap"; // HashMap of String -> URLClassPath.Loader

    private static final String URLCLASSPATH_JARLOADER_INNER_CLASS_NAME = "sun.misc.URLClassPath$JarLoader";
    private static final String URLCLASSPATH_JARLOADER_JARFILE_FIELD_NAME = "jar";
    
    /* Fields used during processing - they can be set up once and then used repeatedly */
    private static Field jcpField;
    private static Field loadersField;
    private static Field urlsField;
    private static Field lmapField;
    private static Class jarLoaderInnerClass;

    private static Field jarFileField;
    
    private static boolean initDone = false;
    
    /**
     *Initializes the class.
     *<p>
     *Each utility method should invoke init() before doing their own work
     *to make sure the initialization is done.
     *@throws any Throwable detected during static init.
     */
    private static void init() throws Throwable {
        if ( ! initDone) {
            initForClosingJars();
            initDone = true;
        }
    }
    
    /**
     *Sets up variables used in closing a loader's jar files.
     *@throws NoSuchFieldException in case a field of interest is not found where expected
     */
    private static void initForClosingJars() throws NoSuchFieldException {
        jcpField = getField(URLClassLoader.class, URLCLASSLOADER_UCP_FIELD_NAME);
        loadersField = getField(URLClassPath.class, URLCLASSPATH_LOADERS_FIELD_NAME);
        urlsField = getField(URLClassPath.class, URLCLASSPATH_URLS_FIELD_NAME);
        lmapField = getField(URLClassPath.class, URLCLASSPATH_LMAP_FIELD_NAME);
        
        jarLoaderInnerClass = getInnerClass(URLClassPath.class, URLCLASSPATH_JARLOADER_INNER_CLASS_NAME);
        jarFileField = getField(jarLoaderInnerClass, URLCLASSPATH_JARLOADER_JARFILE_FIELD_NAME);
    }
    
    /**
     *Retrieves a Field object for a given field on the specified class, having
     *set it accessible.
     *@param cls the Class on which the field is expected to be defined
     *@param fieldName the name of the field of interest
     *@throws NoSuchFieldException in case of any error retriving information about the field
     */
    private static Field getField(Class cls, String fieldName) throws NoSuchFieldException {
        try {
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException nsfe) {
            NoSuchFieldException e = new NoSuchFieldException(getMessage("classloaderutil.errorGettingField", fieldName));
            e.initCause(nsfe);
            throw e;
        }

    }
    
    /**
     *Retrieves a given inner class definition from the specified outer class.
     *@param cls the outer Class
     *@param innerClassName the fully-qualified name of the inner class of interest
     */
    private static Class getInnerClass(Class cls, String innerClassName) {
        Class result = null;
        Class[] innerClasses = cls.getDeclaredClasses();
        for (Class c : innerClasses) {
            if (c.getName().equals(innerClassName)) {
                result = c;
                break;
            }
        }
        return result;
    }
    
    /**
     *Releases resources held by the URLClassLoader.  Notably, close the jars
     *opened by the loader. This does not prevent the class loader from
     *continuing to return classes it has already resolved.
     *@param classLoader the instance of URLClassLoader (or a subclass) 
     *@return array of IOExceptions reporting jars that failed to close
     */
    public static void releaseLoader(URLClassLoader classLoader) {
        releaseLoader(classLoader, null);
    }
    
    /**
     *Releases resources held by the URLClassLoader.  Notably, close the jars
     *opened by the loader. This does not prevent the class loader from
     *continuing to return classes it has alrady resolved although that is not
     *what we intend to happen.  Initializes and updates the Vector of 
     *jars that have been successfully closed.
     *<p>
     *Any errors are logged.
     *@param classLoader the instance of URLClassLoader (or a subclass) 
     *@param jarsClosed a Vector of Strings that will contain the names of jars 
     * successfully closed; can be null if the caller does not need the information returned
     *@return array of IOExceptions reporting jars that failed to close; null
     *indicates that an error other than an IOException occurred attempting to
     *release the loader; empty indicates a successful release; non-empty 
     *indicates at least one error attempting to close an open jar.
     */
    public static IOException [] releaseLoader(URLClassLoader classLoader, Vector<String> jarsClosed) {
        
        IOException[] result = null;
        
        try { 
            init();

            /* Records all IOExceptions thrown while closing jar files. */
            Vector<IOException> ioExceptions = new Vector<IOException>();

            if (jarsClosed != null) {
                jarsClosed.clear();
            }

            URLClassPath ucp = (URLClassPath) jcpField.get(classLoader);
            ArrayList loaders = (ArrayList) loadersField.get(ucp);
            Stack urls = (Stack) urlsField.get(ucp);
            HashMap lmap = (HashMap) lmapField.get(ucp);

            /*
             *The urls variable in the URLClassPath object holds URLs that have not yet
             *been used to resolve a resource or load a class and, therefore, do
             *not yet have a loader associated with them.  Clear the stack so any
             *future requests that might incorrectly reach the loader cannot be 
             *resolved and cannot open a jar file after we think we've closed 
             *them all.
             */
            synchronized(urls) {
                urls.clear();
            }

            /*
             *Also clear the map of URLs to loaders so the class loader cannot use
             *previously-opened jar files - they are about to be closed.
             */
            synchronized(lmap) {
                lmap.clear();
            }

            /*
             *The URLClassPath object's path variable records the list of all URLs that are on
             *the URLClassPath's class path.  Leave that unchanged.  This might
             *help someone trying to debug why a released class loader is still used.
             *Because the stack and lmap are now clear, code that incorrectly uses a
             *the released class loader will trigger an exception if the 
             *class or resource would have been resolved by the class
             *loader (and no other) if it had not been released.  
             *
             *The list of URLs might provide some hints to the person as to where
             *in the code the class loader was set up, which might in turn suggest
             *where in the code the class loader needs to stop being used.
             *The URLClassPath does not use the path variable to open new jar 
             *files - it uses the urls Stack for that - so leaving the path variable
             *will not by itself allow the class loader to continue handling requests.
             */

            /*
             *For each loader, close the jar file associated with that loader.  
             *
             *The URLClassPath's use of loaders is sync-ed on the entire URLClassPath 
             *object.
             */
            synchronized (ucp) {
                for (Object o : loaders) {
                    if (o != null) {
                        /*
                         *If the loader is a JarLoader inner class and its jarFile
                         *field is non-null then try to close that jar file.  Add
                         *it to the list of closed files if successful.
                         */
                        if (jarLoaderInnerClass.isInstance(o)) {
                            try {
                                JarFile jarFile = (JarFile) jarFileField.get(o);
                                try {
                                    if (jarFile != null) {
                                        jarFile.close();
                                        if (jarsClosed != null) {
                                            jarsClosed.add(jarFile.getName());
                                        }
                                    }
                                } catch (IOException ioe) {
                                    /*
                                     *Wrap the IOException to identify which jar 
                                     *could not be closed and add it to the list
                                     *of IOExceptions to be returned to the caller.
                                     */
                                    String jarFileName = (jarFile == null) ? getMessage("classloaderutil.jarFileNameNotAvailable") : jarFile.getName();
                                    String msg = getMessage("classloaderutil.errorClosingJar", jarFileName);
                                    IOException newIOE = new IOException(msg);
                                    newIOE.initCause(ioe);
                                    ioExceptions.add(newIOE);
                                    
                                    /*
                                     *Log the error also.
                                     */
                                    getLogger().log(Level.WARNING, msg, ioe);
                                }
                            } catch (Throwable thr) {
                                getLogger().log(Level.WARNING, "classloaderutil.errorReleasingJarNoName", thr);
                            }
                        }
                    }
                }
                /*
                 *Now clear the loaders ArrayList.
                 */
                loaders.clear();
            }
            result = ioExceptions.toArray(new IOException[ioExceptions.size()]);
        } catch (Throwable thr) {
            getLogger().log(Level.WARNING, "classloaderutil.errorReleasingLoader", thr);
            result = null;
        }
        
        return result;
    }
    
    /**
     *Returns the logger for the common utils component.
     *@return the Logger for this component
     */
    private static Logger getLogger() {
        return LogDomains.getLogger(LogDomains.CMN_LOGGER);
    }
    
    /**
     *Returns a formatted string, using the key to find the full message and 
     *substituting any parameters.
     *@param key the message key with which to locate the message of interest
     *@param o the object(s), if any, to be substituted into the message
     *@return a String containing the formatted message
     */
    private static String getMessage(String key, Object... o) {
        String msg = getLogger().getResourceBundle().getString(key);
        return MessageFormat.format(msg, o);
    }
}
