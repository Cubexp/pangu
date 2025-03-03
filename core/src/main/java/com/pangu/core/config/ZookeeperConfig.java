package com.pangu.core.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ZookeeperConfig {

    private String addr;

    private String rootPath;

    private int serverId;

    private int minStartUp;
}
