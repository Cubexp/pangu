## 分布式游戏架构
### 数据服
1. 保存游戏服数据，包括角色数据，跨服玩法数据
2. 一个数据服负责管理多个数据库，也就是说，一个数据服必须同所有相关数据库部署到同一台机器，暂时不支持管理其他机器数据库；后期可支持管理其他机器数据库(云数据库)
3. 多个数据服会有一个主服概念，主服负责维护中央库的创建，中央服的库由主服进行分配；当添加一个增加服的任务后，由主服将新增的服分配到任意一台数据服；
4. 数据服主要任务除了将数据持久化以外，还需要拥有缓存功能，即所有数据经过相关Api后，数据将存在缓存中，降低mysql读写压力；当缓存中存在脏数据后，持久化线程将会定时检索脏数据，并将其进行持久化；

### 逻辑服
1. 所有玩法逻辑都存在于逻辑服，其中包括用户的登录、创角、竞技场、跨服战等一系列玩法；
2. 逻辑服也存在一定的缓存，当一个用户登录后，会尽力将其绑定到一台逻辑服，将此用户相关的操作顺序化，保证用户的数据变化按顺序进行，以避免锁的使用；

### 网关服
1. 客户端(玩家)直接与网关服进行连接，因为逻辑服存在多种，网关根据对应逻辑服提供的服务ID，将数据包转发到对应逻辑服；
2. 网关服维持在线玩家的session Id，当存在数据推送情况时，其他服务器将对应推送数据发送到网关服，由网关服将数据推送到对应玩家；

### GM工具
1. 提供相应GM工具给运维，用于查询现有网络拓扑，以及连接个数；
2. 手动添加创建服务器(数据服将会自动创建数据库)；
3. 查询在线人数等数据；

### 压力测试服
1. 所有功能完成后，都必须进行压力测试功能编写，类似集成测试；
2. 支持功能自动化运行，在上线前，或者功能验收后，进行并发测试，提前发现并发性问题；