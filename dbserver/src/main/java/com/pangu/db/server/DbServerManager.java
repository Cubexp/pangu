package com.pangu.db.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pangu.core.anno.ComponentDb;
import com.pangu.core.common.Constants;
import com.pangu.core.common.InstanceDetails;
import com.pangu.core.common.ServerInfo;
import com.pangu.core.common.ZookeeperTask;
import com.pangu.core.config.JdbcConfig;
import com.pangu.core.config.ZookeeperConfig;
import com.pangu.db.config.DbConfig;
import com.pangu.db.config.TaskQueueSerializer;
import com.pangu.db.data.service.DbService;
import com.pangu.db.data.service.OnlineService;
import com.pangu.framework.utils.json.JsonUtils;
import com.pangu.framework.utils.lang.ByteUtils;
import com.pangu.framework.utils.math.RandomUtils;
import com.pangu.framework.utils.os.NetUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.barriers.DistributedBarrier;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.queue.DistributedQueue;
import org.apache.curator.framework.recipes.queue.QueueBuilder;
import org.apache.curator.framework.recipes.queue.QueueConsumer;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.x.discovery.*;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;
import org.apache.curator.x.discovery.details.ServiceCacheListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.flywaydb.core.Flyway;
import org.springframework.context.Lifecycle;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.pangu.core.common.Constants.TASK_CREATE_GAME_DATABASE;

@ComponentDb
@Slf4j
public class DbServerManager implements Lifecycle {

    private final DbConfig dbConfig;
    private final DbService dbService;
    private final OnlineService onlineService;

    private final AtomicBoolean running = new AtomicBoolean();
    private CuratorFramework framework;
    private ServiceInstance<InstanceDetails> serviceInstance;
    private ServiceDiscovery<InstanceDetails> serviceDiscovery;
    private ServiceCache<InstanceDetails> dbServerCache;
    private List<ServerInfo> dbServers = new ArrayList<>(1);
    private Set<ServerInfo> gateIds = new HashSet<>();
    private DistributedBarrier barrier;
    private LeaderSelector leaderSelector;
    private ServiceCache<InstanceDetails> gateServerCache;

    public DbServerManager(DbConfig dbConfig, DbService dbService, OnlineService onlineService) {
        this.dbConfig = dbConfig;
        this.dbService = dbService;
        this.onlineService = onlineService;
    }

    @Override
    public void start() {
        boolean set = running.compareAndSet(false, true);
        if (!set) {
            return;
        }
        ZookeeperConfig zookeeper = dbConfig.getZookeeper();
        framework = CuratorFrameworkFactory.builder()
                .connectString(zookeeper.getAddr())
                .sessionTimeoutMs(20_000)
                .connectionTimeoutMs(10_000)
                .defaultData(ByteUtils.intToByte(zookeeper.getServerId()))
                .retryPolicy(new RetryOneTime(1000))
                .build();
        framework.start();

        barrier = new DistributedBarrier(framework, Constants.DB_MINI_STARTUP_BARRIER);
        log.debug("DB等待最少服务启动");
        try {
            barrier.setBarrier();
        } catch (Exception e) {
            log.warn("等待其他数据服启动失败");
        }
        try {
            registerServer();
            initDBDiscovery();
            initGateDiscovery();
        } catch (Exception e) {
            log.warn("注册服务异常", e);
        }
        registerWatchManaged();
        try {
            barrier.waitOnBarrier();
            barrier = null;
        } catch (Exception e) {
            log.warn("同时启动兼容支持异常", e);
        }

        startLeaderElection();
    }

    private void initGateDiscovery() throws Exception {
        gateServerCache = serviceDiscovery
                .serviceCacheBuilder()
                .name(Constants.GATEWAY_SERVICE_NAME)
                .build();
        gateServerCache.start();

        initGateServerService(gateServerCache);

        log.debug("首次刷新网关服务器列表[{}]", dbServers);
        gateServerCache.addListener(new ServiceCacheListener() {
            @Override
            public void cacheChanged() {
                initGateServerService(gateServerCache);
            }

            @Override
            public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                log.debug("Zookeeper状态改变[{}]", connectionState);
            }
        });
    }

    private void registerWatchManaged() {
        ZookeeperConfig config = dbConfig.getZookeeper();
        String path = config.getRootPath() + Constants.DB_MANAGE_LIST + "/" + config.getServerId();
        try {
            framework.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path, JsonUtils.object2Bytes(Collections.emptyList()));
        } catch (Exception ignore) {
        }
        try {
            byte[] bytes = framework.getData().usingWatcher(new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getType() != Event.EventType.NodeDataChanged) {
                        return;
                    }
                    try {
                        byte[] curByte = framework.getData().usingWatcher(this).forPath(path);
                        updateManagedServer(curByte);
                    } catch (Exception e) {
                        log.error("监听本节点变更异常", e);
                    }
                }
            }).forPath(path);
            updateManagedServer(bytes);
        } catch (Exception e) {
            log.error("监听本节点变更异常", e);
        }
    }

    private void updateManagedServer(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        Set<String> managedServerIds = JsonUtils.bytes2GenericObject(bytes, new TypeReference<Set<String>>() {
        });
        for (String serverId : managedServerIds) {
            if (Constants.CENTER_DATABASE_NAME.equals(serverId)) {
                updateCenterDatabase();
            } else {
                updateGameDatabase(serverId);
            }
        }
        serviceInstance.getPayload().setDescription(new String(bytes, StandardCharsets.UTF_8));

        try {
            serviceDiscovery.updateService(serviceInstance);
        } catch (Exception e) {
            log.warn("更新实例异常", e);
        }

        dbService.updateManagedServerIds(managedServerIds);
        log.info("DB Server[{}]管理服节点为{}", dbConfig.getZookeeper().getServerId(), new String(bytes));
    }

    private void registerServer() throws Exception {
        String address = dbConfig.getSocket().getAddress();
        String[] split = address.trim().split(":");
        if (split.length == 0) {
            throw new IllegalStateException("服务器配置 server.address 配置为空，配置格式: 内网IP:端口，如192.168.11.88:8001");
        }
        String ip;
        if (split.length <= 1) {
            InetAddress localAddress = NetUtils.getLocalAddress();
            ip = localAddress.getHostAddress();
        } else {
            ip = split[0];
            if (StringUtils.isBlank(ip) || !NetUtils.validIp(ip)) {
                InetAddress localAddress = NetUtils.getLocalAddress();
                ip = localAddress.getHostAddress();
            }
        }
        int id = dbConfig.getZookeeper().getServerId();

        ServiceInstanceBuilder<InstanceDetails> builder = ServiceInstance.<InstanceDetails>builder()
                .id(String.valueOf(id))
                .name(Constants.DB_SERVICE_NAME)
                .address(ip)
                .port(Integer.parseInt(split[1]))
                .payload(new InstanceDetails());
        serviceInstance = builder.build();

        JsonInstanceSerializer<InstanceDetails> serializer = new JsonInstanceSerializer<>(InstanceDetails.class);
        ZookeeperConfig zookeeper = dbConfig.getZookeeper();
        serviceDiscovery = ServiceDiscoveryBuilder.builder(InstanceDetails.class)
                .client(framework)
                .basePath(zookeeper.getRootPath())
                .serializer(serializer)
                .thisInstance(serviceInstance)
                .build();
        serviceDiscovery.start();
    }

    private void updateGameDatabase(String serverId) {
        JdbcConfig jdbc = dbConfig.getJdbc();
        Flyway flyway = Flyway.configure()
                .schemas(jdbc.getDatabasePrefix() + serverId)
                .locations("classpath:db/game")
                .dataSource("jdbc:mysql://" + jdbc.getAddr() + jdbc.getParams(), jdbc.getUsername(), jdbc.getPassword())
                .load();
        flyway.migrate();
    }

    private void updateCenterDatabase() {
        JdbcConfig jdbc = dbConfig.getJdbc();
        Flyway flyway = Flyway.configure()
                .schemas(jdbc.getDatabasePrefix() + Constants.CENTER_DATABASE_NAME)
                .locations("classpath:db/center")
                .dataSource("jdbc:mysql://" + jdbc.getAddr() + jdbc.getParams(), jdbc.getUsername(), jdbc.getPassword())
                .load();
        flyway.migrate();
    }

    private void initDBDiscovery() throws Exception {
        dbServerCache = serviceDiscovery
                .serviceCacheBuilder()
                .name(Constants.DB_SERVICE_NAME)
                .build();
        dbServerCache.start();

        initDBServerService(dbServerCache);

        log.debug("首次刷新数据服务器列表[{}]", dbServers);
        dbServerCache.addListener(new ServiceCacheListener() {
            @Override
            public void cacheChanged() {
                initDBServerService(dbServerCache);
            }

            @Override
            public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                log.debug("Zookeeper状态改变[{}]", connectionState);
            }
        });
    }

    private void initDBServerService(ServiceCache<InstanceDetails> cache) {
        List<ServiceInstance<InstanceDetails>> instances = cache.getInstances();
        List<ServerInfo> servers = new ArrayList<>();

        for (ServiceInstance<InstanceDetails> instance : instances) {
            InstanceDetails payload = instance.getPayload();
            ServerInfo serverInfo = new ServerInfo(instance.getId(), instance.getAddress(), instance.getPort(), payload.getAddressForClient(), payload);
            servers.add(serverInfo);
        }
        dbServers = servers;
        if (servers.size() >= dbConfig.getZookeeper().getMinStartUp()) {
            try {
                if (barrier != null) {
                    barrier.removeBarrier();
                }
            } catch (Exception e) {
                log.warn("同时启动兼容支持异常", e);
            }
        }
        log.debug("当前数据服列表[{}]", servers);
    }

    private void initGateServerService(ServiceCache<InstanceDetails> cache) {
        List<ServiceInstance<InstanceDetails>> instances = cache.getInstances();
        Set<ServerInfo> currents = new HashSet<>();

        for (ServiceInstance<InstanceDetails> instance : instances) {
            InstanceDetails payload = instance.getPayload();
            ServerInfo serverInfo = new ServerInfo(instance.getId(), instance.getAddress(), instance.getPort(), payload.getAddressForClient(), payload);
            currents.add(serverInfo);
        }
        HashSet<ServerInfo> removed = new HashSet<>(this.gateIds);
        HashSet<ServerInfo> add = new HashSet<>(currents);

        removed.removeAll(add);

        add.removeAll(this.gateIds);

        this.gateIds = currents;

        onlineService.gateUpdate(add, removed);

        log.debug("当前网关数据服列表[{}]", currents);
    }

    private void startLeaderElection() {
        leaderSelector = new LeaderSelector(framework, dbConfig.getZookeeper().getRootPath() + Constants.DB_LEADER_PATH, new LeaderSelectorListener() {
            @Override
            public void takeLeadership(CuratorFramework client) {
                try {
                    log.info("开始执行Leader职责");
                    startLeaderJob();
                } catch (Exception e) {
                    log.error("DB Server执行主服逻辑异常", e);
                }
            }

            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
            }
        });
        try {
            leaderSelector.start();
            leaderSelector.autoRequeue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void startLeaderJob() throws Exception {
        ZookeeperConfig zookeeper = dbConfig.getZookeeper();
        String manageServerParentPath = zookeeper.getRootPath() + Constants.DB_MANAGE_LIST;
        List<String> serverIds = framework.getChildren().forPath(manageServerParentPath);
        boolean hasCenter = false;
        for (String serverId : serverIds) {
            String serverManagedPath = manageServerParentPath + "/" + serverId;
            byte[] bytes = framework.getData().forPath(serverManagedPath);
            if (bytes == null) {
                continue;
            }
            Set<String> managedServerIds = JsonUtils.bytes2GenericObject(bytes, new TypeReference<Set<String>>() {
            });
            hasCenter = managedServerIds != null && managedServerIds.contains(Constants.CENTER_DATABASE_NAME);
        }
        if (!hasCenter) {
            if (dbServers.size() == 0) {
                // 防止死循环
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ignore) {
                }
                return;
            }
            ServerInfo serverInfo = dbServers.get(0);
            leaderDispatchGameDB(serverInfo.getId(), Constants.CENTER_DATABASE_NAME);
        }
        String masterTaskQueuePath = zookeeper.getRootPath() + Constants.DB_MASTER_TASK_QUEUE;

        DistributedQueue<ZookeeperTask> queue = QueueBuilder.builder(framework, new QueueConsumer<ZookeeperTask>() {
            @Override
            public void consumeMessage(ZookeeperTask message) {
                if (TASK_CREATE_GAME_DATABASE.equals(message.getType())) {
                    int size = dbServers.size();
                    if (size == 0) {
                        log.warn("没有找到有效DB，忽视任务");
                        return;
                    }
                    ServerInfo serverInfo = dbServers.get(RandomUtils.nextInt(size));
                    try {
                        leaderDispatchGameDB(serverInfo.getId(), message.getParams());
                        log.warn("分配服务器[{}]到数据服[{}]", message.getParams(), serverInfo.getId());
                    } catch (Exception e) {
                        log.warn("分配服务器[{}]到数据服[{}]失败", message.getParams(), serverInfo.getId());
                    }
                } else {
                    log.warn("不支持的任务类型[{}]", message);
                }
            }

            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {

            }
        }, new TaskQueueSerializer(), masterTaskQueuePath).buildQueue();
        queue.start();
        try {
            while (running.get() && leaderSelector.hasLeadership() && framework.getZookeeperClient().isConnected()) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(500);
                } catch (InterruptedException inter) {
                    break;
                }
            }
        } finally {
            queue.close();
        }
    }

    private void leaderDispatchGameDB(String dbId, String serverId) throws Exception {
        ZookeeperConfig zookeeper = dbConfig.getZookeeper();
        String manageServerParentPath = zookeeper.getRootPath() + Constants.DB_MANAGE_LIST;
        String serverManagedPath = manageServerParentPath + "/" + dbId;
        byte[] bytes = framework.getData().forPath(serverManagedPath);
        Set<String> managedServerIds;
        if (bytes == null) {
            managedServerIds = new HashSet<>(1);
        } else {
            managedServerIds = JsonUtils.bytes2GenericObject(bytes, new TypeReference<Set<String>>() {
            });
        }

        managedServerIds.add(serverId);
        framework.setData().forPath(serverManagedPath, JsonUtils.object2Bytes(managedServerIds));
    }

    @Override
    public void stop() {
        boolean set = running.compareAndSet(true, false);
        if (!set) {
            return;
        }
        if (dbServerCache != null) {
            CloseableUtils.closeQuietly(dbServerCache);
        }
        CloseableUtils.closeQuietly(gateServerCache);
        try {
            serviceDiscovery.unregisterService(serviceInstance);
        } catch (Exception e) {
            log.debug("取消注册服务[{}]", Constants.DB_SERVICE_NAME, e);
        }
        CloseableUtils.closeQuietly(serviceDiscovery);
        if (leaderSelector.hasLeadership()) {
            leaderSelector.interruptLeadership();
        }
        CloseableUtils.closeQuietly(leaderSelector);
        if (framework != null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignore) {
            }
            CloseableUtils.closeQuietly(framework);
        }
        log.debug("服务器[{}]取消注册进入服务器", Constants.DB_SERVICE_NAME);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
