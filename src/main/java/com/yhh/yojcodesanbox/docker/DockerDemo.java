package com.yhh.yojcodesanbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;

import java.util.List;

/**
 * @author hyh
 * @date 2024/6/6
 */
public class DockerDemo {

    public static void main(String[] args) throws InterruptedException {

        // 获取默认的 Docker Client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

//            PingCmd pingCmd = dockerClient.pingCmd();
//            pingCmd.exec();

        // 1 拉取镜像
        String image = "nginx:latest";
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);

        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                System.out.println("拉取镜像：" + item.getStatus());
                super.onNext(item);
            }
        };

        pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
        System.out.println("拉取完成");

        //2 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse createContainerResponse = containerCmd.withCmd("echo", "Hello, Docker!").exec();
        System.out.println("createContainerResponse = " + createContainerResponse);
        String containerId = createContainerResponse.getId();

        //查看容器状态
        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd();
        List<Container> containersList = listContainersCmd.withShowAll(true).exec();
        for (Container container : containersList) {
            System.out.println("container = " + container);
        }

        //启动容器
        dockerClient.startContainerCmd(containerId).exec();

    //查看日志
        LogContainerResultCallback logContainerResultCallback = new LogContainerResultCallback() {
            @Override
            public void onNext(Frame item) {
                System.out.println(item.getStreamType());
                System.out.println("日志：" + new String(item.getPayload()));
                super.onNext(item);
            }
        };
        dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .exec(logContainerResultCallback);

    //删除容器
        dockerClient.removeContainerCmd(containerId).exec();

    //删除镜像
    //dockerClient.removeImageCmd(image).exec();

    }
}