/*
 * Copyright (c) 2007-2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.sonatype.eclipse.bridge.internal.instance;

import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.core.runtime.adaptor.EclipseStarter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.sonatype.eclipse.bridge.EclipseInstance;
import org.sonatype.eclipse.bridge.EclipseLocation;

public class DefaultEclipseInstance
    implements EclipseInstance
{
    private EclipseInstance state;

    private final EclipseLocation location;

    private final Lock eclipseLock;

    public DefaultEclipseInstance( final EclipseLocation location )
    {
        this.location = location;

        state = new Stopped();
        eclipseLock = new ReentrantLock( true );
    }

    public <T> T getService( final Class<T> serviceType )
    {
        return state.getService( serviceType );
    }

    public Long installBundle( final String location )
    {
        return state.installBundle( location );
    }

    public Long installBundle( final String location, final InputStream inputStream )
    {
        return state.installBundle( location, inputStream );
    }

    public void startBundle( final Long id )
    {
        state.startBundle( id );
    }

    public EclipseInstance shutdown()
    {
        return state.shutdown();
    }

    public EclipseInstance start( final Map<String, String> launchProperties )
    {
        return state.start( launchProperties );
    }

    private class Started
        implements EclipseInstance
    {

        private final Map<WeakReference<?>, ServiceReference> activeServices;

        private final ReferenceQueue<Object> staleReferences;

        private final ReadWriteLock lock;

        private final Thread cleanupThread;

        public Started()
        {
            activeServices = new HashMap<WeakReference<?>, ServiceReference>();
            staleReferences = new ReferenceQueue<Object>();
            lock = new ReentrantReadWriteLock();
            cleanupThread = new Thread( new Cleanup() );
            cleanupThread.start();
        }

        public <T> T getService( final Class<T> serviceType )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                final BundleContext bundleContext = EclipseStarter.getSystemBundleContext();
                final ServiceReference serviceReference = bundleContext.getServiceReference( serviceType.getName() );
                if ( serviceReference == null )
                {
                    throw new IllegalStateException( String.format( "There is no service available of type %s",
                        serviceType ) );
                }
                final T service = serviceType.cast( bundleContext.getService( serviceReference ) );
                if ( service == null )
                {
                    throw new IllegalStateException( String.format( "There is no service available of type %s",
                        serviceType ) );
                }
                activeServices.put( new WeakReference<T>( service, staleReferences ), serviceReference );
                return service;
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        public Long installBundle( final String location )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                final BundleContext bundleContext = EclipseStarter.getSystemBundleContext();
                final Bundle bundle = bundleContext.installBundle( location );
                return bundle.getBundleId();
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        public Long installBundle( final String location, final InputStream inputStream )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                final BundleContext bundleContext = EclipseStarter.getSystemBundleContext();
                final Bundle bundle = bundleContext.installBundle( location, inputStream );
                return bundle.getBundleId();
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        public void startBundle( final Long id )
        {
            try
            {
                lock.readLock().lock();
                if ( state != this )
                {
                    throw new RuntimeException( "Eclipse instance is now longer valid" );
                }
                final BundleContext bundleContext = EclipseStarter.getSystemBundleContext();
                final Bundle bundle = bundleContext.getBundle( id );
                bundle.start();
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        public EclipseInstance shutdown()
        {
            try
            {
                lock.writeLock().lock();
                EclipseStarter.shutdown();
                eclipseLock.unlock();
                cleanupThread.interrupt();
                state = new Stopped();
                return state;
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                lock.writeLock().unlock();
            }
        }

        public EclipseInstance start( final Map<String, String> launchProperties )
        {
            return this;
        }

        private class Cleanup
            implements Runnable
        {

            public void run()
            {
                try
                {
                    final Reference<? extends Object> obsolete = staleReferences.remove();
                    final ServiceReference reference = activeServices.remove( obsolete );
                    EclipseStarter.getSystemBundleContext().ungetService( reference );
                }
                catch ( final Exception ignore )
                {
                    // we did our best
                }
            }

        }

    }

    private class Stopped
        implements EclipseInstance
    {

        public <T> T getService( final Class<T> serviceType )
        {
            // TODO shall it fail since not started?
            return null;
        }

        public Long installBundle( final String location )
        {
            // TODO shall it fail since not started?
            return null;
        }

        public Long installBundle( final String location, final InputStream inputStream )
        {
            // TODO shall it fail since not started?
            return null;
        }

        public void startBundle( final Long id )
        {
            // TODO shall it fail since not started?
        }

        public EclipseInstance shutdown()
        {
            return this;
        }

        public EclipseInstance start( final Map<String, String> launchProperties )
        {
            try
            {
                if ( !eclipseLock.tryLock() )
                {
                    throw new IllegalStateException( "Eclipse instance already in use" );
                }

                final String eclipseLocation = location.get().getAbsolutePath();

                final Map<String, String> properties = new HashMap<String, String>();
                if ( launchProperties != null )
                {
                    properties.putAll( launchProperties );
                }
                properties.put( EclipseStarter.PROP_INSTALL_AREA, eclipseLocation );
                properties.put( EclipseStarter.PROP_SYSPATH, eclipseLocation + "/plugins" );
                properties.put( "osgi.parentClassloader", "fwk" );

                System.setProperty( "osgi.framework.useSystemProperties", "false" );

                EclipseStarter.setInitialProperties( properties );
                EclipseStarter.startup( new String[0], null );

                state = new Started();

                return state;
            }
            catch ( final Exception e )
            {
                throw new RuntimeException( e );
            }
        }

    }

}
