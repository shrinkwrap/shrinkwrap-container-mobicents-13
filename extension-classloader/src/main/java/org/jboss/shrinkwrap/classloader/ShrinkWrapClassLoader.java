/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.classloader;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.vfs3.ArchiveFileSystem;
import org.jboss.vfs.TempDir;
import org.jboss.vfs.TempFileProvider;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;

/**
 * Extension that will create a ClassLoader based on a Array of Archives
 *
 * @author <a href="mailto:aslak@redhat.com">Aslak Knutsen</a>
 * @author <a href="mailto:andrew.rubinger@jboss.org">ALR</a>
 */
public class ShrinkWrapClassLoader extends URLClassLoader implements Closeable
{
   //-------------------------------------------------------------------------------------||
   // Class Members ----------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Logger
    */
   private static final Logger log = Logger.getLogger(ShrinkWrapClassLoader.class.getName());

   //-------------------------------------------------------------------------------------||
   // Instance Members -------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * All open VFS handles to close when {@link ShrinkWrapClassLoader#close()}
    * is invoked
    */
   private Set<Closeable> vfsHandlesToClose = new HashSet<Closeable>();

   /**
    * {@link ExecutorService}s to shutdown when {@link ShrinkWrapClassLoader#close()}
    * is invoked
    */
   private Set<ExecutorService> executorServicesToShutdown = new HashSet<ExecutorService>();

   //-------------------------------------------------------------------------------------||
   // Constructors -----------------------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * Constructs a new ShrinkWrapClassLoader for the specified {@link Archive}s using the
    * default delegation parent <code>ClassLoader</code>. The {@link Archive}s will
    * be searched in the order specified for classes and resources after
    * first searching in the parent class loader.
    * 
    * @param archives the {@link Archive}s from which to load classes and resources
    */
   public ShrinkWrapClassLoader(final Archive<?>... archives)
   {
      super(new URL[]{});

      if (archives == null)
      {
         throw new IllegalArgumentException("Archives must be specified");
      }
      addArchives(archives);
   }

   /**
    * Constructs a new ShrinkWrapClassLoader for the given {@link Archive}s. The {@link Archive}s will be
    * searched in the order specified for classes and resources after first
    * searching in the specified parent class loader. 
    * 
    * @param parent the parent class loader for delegation
    * @param archives the {@link Archive}s from which to load classes and resources
    */
   public ShrinkWrapClassLoader(final ClassLoader parent, final Archive<?>... archives)
   {
      super(new URL[]{}, parent);

      if (archives == null)
      {
         throw new IllegalArgumentException("Archives must be specified");
      }
      addArchives(archives);
   }

   private void addArchives(final Archive<?>[] archives)
   {
      for (final Archive<?> archive : archives)
      {
         addArchive(archive);
      }
   }

   private void addArchive(final Archive<?> archive)
   {
      // TODO: Wrap a ExecutorService in a ScheduledExecutorService 
      //Configuration configuration = archive.as(Configurable.class).getConfiguration();
      ScheduledExecutorService executorService = null; //configuration.getExecutorService();
      if (executorService == null)
      {
         executorService = Executors.newScheduledThreadPool(2);

         // TODO: only add to 'managed' executor services if it was created here..

         // add to list of resources to cleanup during close()
         executorServicesToShutdown.add(executorService);
      }

      try
      {
         final TempFileProvider tempFileProvider = TempFileProvider.create("shrinkwrap-classloader", executorService);

         final TempDir tempDir = tempFileProvider.createTempDir(archive.getName());
         final VirtualFile virtualFile = VFS.getChild(UUID.randomUUID().toString()).getChild(archive.getName());

         final Closeable handle = VFS.mount(virtualFile, new ArchiveFileSystem(archive, tempDir));

         // add to list of resources to cleanup during close()
         vfsHandlesToClose.add(handle);

         addURL(virtualFile.toURL());

      }
      catch (final IOException e)
      {
         throw new RuntimeException("Could not create ClassLoader from archive: " + archive.getName(), e);
      }
   }

   /**
    * {@inheritDoc}
    * @see java.io.Closeable#close()
    */
   public void close() throws IOException
   {
      // Unmount all VFS3 mount points
      for (final Closeable handle : vfsHandlesToClose)
      {
         try
         {
            handle.close();
         }
         catch (final IOException e)
         {
            log.warning("Could not close VFS handle: " + e);
         }
      }
      vfsHandlesToClose.clear();
      
      // Shutdown all created Executor Services.
      for (final ExecutorService executorService : executorServicesToShutdown)
      {
         executorService.shutdownNow();
      }
      executorServicesToShutdown.clear();
   }
}
