package com.pangu.stress.module;

import com.pangu.core.anno.ComponentStress;
import com.pangu.core.common.ServerInfo;
import com.pangu.framework.socket.client.Client;
import com.pangu.framework.socket.client.ClientFactory;
import com.pangu.framework.utils.codec.CryptUtils;
import com.pangu.framework.utils.model.Result;
import com.pangu.logic.module.account.facade.AccountFacade;
import com.pangu.logic.module.account.model.Sex;
import com.pangu.stress.config.StressConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@ComponentStress
@Slf4j
public class StressManager {

    private List<ServerInfo> gateServers;

    private final ClientFactory clientFactory;
    private final StressConfig stressConfig;

    private boolean once = false;

    public StressManager(ClientFactory clientFactory, StressConfig stressConfig) {
        this.clientFactory = clientFactory;
        this.stressConfig = stressConfig;
    }

    public void serverChange(List<ServerInfo> gateServers) {
        this.gateServers = gateServers;
        if (gateServers == null || gateServers.isEmpty()) {
            log.info("没有网关服，忽视登录");
            return;
        }
        if (once) {
            return;
        }
        once = true;
        login(gateServers.get(0));
    }

    private void login(ServerInfo serverInfo) {
        Client client = clientFactory.getClient(serverInfo.getAddress());
        AccountFacade account = client.getProxy(AccountFacade.class);
        String uid = "t1";
        String accountName = uid + ".1_1";
        Result<Boolean> t1 = account.checkAccount(accountName);
        if (t1 == null || t1.getCode() < 0) {
            log.info("[{}]登录失败[{}]", accountName, t1 == null ? -9999 : t1.getCode());
            return;
        }
        if (!t1.getContent()) {
            Result<Void> result = account.create(accountName, accountName, Sex.FEMALE, "");
            if (result.getCode() < 0) {
                log.info("[{}]创建账号失败", result.getCode());
                return;
            }
        }
        String loginKey = stressConfig.getSystem().getLoginKey();
        long time = System.currentTimeMillis() / 1000;
        String sign = CryptUtils.md5(uid + time + loginKey);
        Result<Void> login = account.login(accountName, true, time, sign, null);
        if (login.getCode() < 0) {
            log.info("[{}]登录失败[{}]", accountName, login.getCode());
            return;
        }
        long start = System.currentTimeMillis();
        for (int i = 1; i < Integer.MAX_VALUE; ++i) {
            time = System.currentTimeMillis() / 1000;
            sign = CryptUtils.md5(uid + time + loginKey);
            login = account.login(accountName, true, time, sign, null);
            if (login.getCode() < 0) {
                log.info("[{}]登录失败[{}]", accountName, login.getCode());
            }
            if (i % 1000 == 0) {
                log.info("登录1000次耗时:" + (System.currentTimeMillis() - start) + "ms");
                start = System.currentTimeMillis();
            }
        }
        log.info("[{}]登录成功", accountName);
    }
}
