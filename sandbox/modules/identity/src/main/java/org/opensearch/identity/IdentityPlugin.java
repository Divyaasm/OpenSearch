/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.identity;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionResponse;
import org.opensearch.action.support.ActionFilter;
import org.opensearch.authn.AuthenticationManager;
import org.opensearch.authn.Identity;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.component.Lifecycle;
import org.opensearch.common.component.LifecycleComponent;
import org.opensearch.common.component.LifecycleListener;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.extensions.ExtensionsManager;
import org.opensearch.identity.authmanager.internal.InternalAuthenticationManager;
import org.opensearch.identity.authz.IndexNameExpressionResolverHolder;
import org.opensearch.identity.configuration.ClusterInfoHolder;
import org.opensearch.identity.configuration.ConfigurationRepository;
import org.opensearch.identity.configuration.DynamicConfigFactory;
import org.opensearch.identity.rest.permission.put.PutPermissionAction;
import org.opensearch.identity.rest.permission.put.RestPutPermissionAction;
import org.opensearch.identity.rest.permission.put.TransportPutPermissionAction;
import org.opensearch.identity.jwt.IdentityJwtVerifier;
import org.opensearch.identity.rest.configuration.IdentityConfigUpdateAction;
import org.opensearch.identity.rest.configuration.TransportIdentityConfigUpdateAction;
import org.opensearch.identity.rest.user.delete.DeleteUserAction;
import org.opensearch.identity.rest.user.delete.RestDeleteUserAction;
import org.opensearch.identity.rest.user.delete.TransportDeleteUserAction;
import org.opensearch.identity.rest.user.get.multi.MultiGetUserAction;
import org.opensearch.identity.rest.user.get.multi.RestMultiGetUserAction;
import org.opensearch.identity.rest.user.get.multi.TransportMultiGetUserAction;
import org.opensearch.identity.rest.user.get.single.GetUserAction;
import org.opensearch.identity.rest.user.get.single.RestGetUserAction;
import org.opensearch.identity.rest.user.get.single.TransportGetUserAction;
import org.opensearch.identity.rest.user.put.PutUserAction;
import org.opensearch.identity.rest.user.put.RestPutUserAction;
import org.opensearch.identity.rest.user.put.TransportPutUserAction;
import org.opensearch.identity.rest.user.resetpassword.ResetPasswordAction;
import org.opensearch.identity.rest.user.resetpassword.RestResetPasswordAction;
import org.opensearch.identity.rest.user.resetpassword.TransportResetPasswordAction;
import org.opensearch.indices.SystemIndexDescriptor;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.ClusterPlugin;
import org.opensearch.plugins.NetworkPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SystemIndexPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class IdentityPlugin extends Plugin implements ActionPlugin, NetworkPlugin, SystemIndexPlugin, ClusterPlugin {
    private volatile Logger log = LogManager.getLogger(this.getClass());

    private volatile SecurityRestFilter securityRestHandler;

    private final boolean enabled;
    private volatile Settings settings;

    private volatile Path configPath;
    private volatile SecurityFilter sf;
    private volatile ThreadPool threadPool;

    private volatile ConfigurationRepository cr;
    private volatile ClusterService cs;
    private volatile Client localClient;
    private volatile NamedXContentRegistry namedXContentRegistry = null;

    @SuppressWarnings("removal")
    public IdentityPlugin(final Settings settings, final Path configPath) {
        enabled = isEnabled(settings);

        if (!enabled) {
            log.warn("Identity module is disabled.");
            return;
        }

        this.configPath = configPath;

        if (this.configPath != null) {
            log.info("OpenSearch Config path is {}", this.configPath.toAbsolutePath());
        } else {
            log.info("OpenSearch Config path is not set");
        }

        this.settings = settings;
    }

    private static boolean isEnabled(final Settings settings) {
        return settings.getAsBoolean(IdentityConfigConstants.IDENTITY_ENABLED, false);
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        if (!enabled) {
            return Collections.emptyList();
        }
        final List<RestHandler> handlers = new ArrayList<>(5);
        handlers.add(new RestPutUserAction());
        handlers.add(new RestGetUserAction());
        handlers.add(new RestMultiGetUserAction());
        handlers.add(new RestDeleteUserAction());
        handlers.add(new RestPutPermissionAction());
        handlers.add(new RestResetPasswordAction());
        // TODO: Add handlers for future actions
        return handlers;
    }

    // register actions in this plugin
    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {

        // required to prevent GuiceHolder inject errors
        if (!enabled) {
            return Collections.emptyList();
        }

        return Arrays.asList(
            new ActionHandler<>(IdentityConfigUpdateAction.INSTANCE, TransportIdentityConfigUpdateAction.class),
            new ActionHandler<>(PutUserAction.INSTANCE, TransportPutUserAction.class),
            new ActionHandler<>(GetUserAction.INSTANCE, TransportGetUserAction.class),
            new ActionHandler<>(MultiGetUserAction.INSTANCE, TransportMultiGetUserAction.class),
            new ActionHandler<>(DeleteUserAction.INSTANCE, TransportDeleteUserAction.class),
            new ActionHandler<>(PutPermissionAction.INSTANCE, TransportPutPermissionAction.class),
            new ActionHandler<>(ResetPasswordAction.INSTANCE, TransportResetPasswordAction.class)
        );
    }

    @Override
    public UnaryOperator<RestHandler> getRestHandlerWrapper(final ThreadContext threadContext) {
        if (!enabled) {
            return (rh) -> rh;
        }
        return (rh) -> securityRestHandler.wrap(rh);
    }

    @Override
    public List<ActionFilter> getActionFilters() {
        List<ActionFilter> filters = new ArrayList<>(1);
        if (!enabled) {
            return filters;
        }
        filters.add(Objects.requireNonNull(sf));
        return filters;
    }

    @Override
    public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
        final String indexPattern = settings.get(
            IdentityConfigConstants.IDENTITY_CONFIG_INDEX_NAME,
            IdentityConfigConstants.IDENTITY_DEFAULT_CONFIG_INDEX
        );
        final SystemIndexDescriptor systemIndexDescriptor = new SystemIndexDescriptor(indexPattern, "Identity index");
        return Collections.singletonList(systemIndexDescriptor);
    }

    public List<Setting<?>> getSettings() {
        List<Setting<?>> settings = new ArrayList<Setting<?>>();
        settings.addAll(super.getSettings());
        settings.add(
            Setting.boolSetting(IdentityConfigConstants.IDENTITY_ENABLED, false, Setting.Property.NodeScope, Setting.Property.Filtered)
        );
        settings.add(
            Setting.simpleString(
                IdentityConfigConstants.IDENTITY_AUTH_MANAGER_CLASS,
                InternalAuthenticationManager.class.getCanonicalName(),
                Setting.Property.NodeScope,
                Setting.Property.Filtered
            )
        );
        settings.add(
            Setting.simpleString(IdentityConfigConstants.IDENTITY_SIGNING_KEY, Setting.Property.NodeScope, Setting.Property.Filtered)
        );
        settings.add(
            Setting.simpleString(IdentityConfigConstants.IDENTITY_CONFIG_INDEX_NAME, Setting.Property.NodeScope, Setting.Property.Filtered)
        );

        return settings;
    }

    @Override
    public void onNodeStarted() {
        log.info("Node started");
        if (enabled) {
            cr.initOnNodeStart();
        }
    }

    @Override
    public Collection<Class<? extends LifecycleComponent>> getGuiceServiceClasses() {

        if (!enabled) {
            return Collections.emptyList();
        }

        final List<Class<? extends LifecycleComponent>> services = new ArrayList<>(1);
        services.add(GuiceHolder.class);
        return services;
    }

    public static class GuiceHolder implements LifecycleComponent {

        private static ExtensionsManager extensionsManager;

        @Inject
        public GuiceHolder(final ExtensionsManager extensionsManager) {
            GuiceHolder.extensionsManager = extensionsManager;
        }

        public static ExtensionsManager getExtensionsManager() {
            return extensionsManager;
        }

        @Override
        public void close() {}

        @Override
        public Lifecycle.State lifecycleState() {
            return null;
        }

        @Override
        public void addLifecycleListener(LifecycleListener listener) {}

        @Override
        public void removeLifecycleListener(LifecycleListener listener) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}

    }

    @Override
    public Collection<Object> createComponents(
        Client localClient,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        // TODO The constructor is not getting called in time leaving these values as null when creating the ConfigurationRepository
        // Can the constructor be substituted by taking these from environment?
        this.configPath = environment.configDir();
        this.settings = environment.settings();
        IndexNameExpressionResolverHolder.setIndexNameExpressionResolver(indexNameExpressionResolver);

        // TODO: revisit this
        final String authManagerClassName = this.settings.get(
            IdentityConfigConstants.IDENTITY_AUTH_MANAGER_CLASS,
            InternalAuthenticationManager.class.getCanonicalName()
        );
        AuthenticationManager authManager = null;
        try {
            Class<?> clazz = Class.forName(authManagerClassName);
            authManager = (AuthenticationManager) clazz.getConstructor().newInstance();

            try {
                Method method = clazz.getMethod("setThreadPool", ThreadPool.class);
                method.invoke(authManager, threadPool);
            } catch (NoSuchMethodException e) {
                /** ignore */
            }

            Identity.setAuthManager(authManager);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        this.threadPool = threadPool;
        this.cs = clusterService;
        this.localClient = localClient;

        final List<Object> components = new ArrayList<Object>();

        if (!enabled) {
            return components;
        }

        final ClusterInfoHolder cih = new ClusterInfoHolder();
        this.cs.addListener(cih);

        sf = new SecurityFilter(localClient, settings, threadPool, cs);

        securityRestHandler = new SecurityRestFilter(threadPool, settings, configPath);

        cr = ConfigurationRepository.create(settings, this.configPath, threadPool, localClient, clusterService);

        final DynamicConfigFactory dcf = new DynamicConfigFactory(cr, settings, configPath, localClient, threadPool, cih);
        // TODO Register DCF listeners to dynamically load config
        // dcf.registerDCFListener(securityRestHandler);

        cr.setDynamicConfigFactory(dcf);

        IdentityJwtVerifier verifier = IdentityJwtVerifier.getInstance();
        verifier.init(this.settings.get(IdentityConfigConstants.IDENTITY_SIGNING_KEY));

        // required for dependency injections
        components.add(cr);
        components.add(dcf);

        return components;
    }
}