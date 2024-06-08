package com.yhh.yojcodesanbox.codesanboxdemo;


import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.yhh.yojcodesanbox.CodeSandbox;
import com.yhh.yojcodesanbox.model.ExecuteCodeRequest;
import com.yhh.yojcodesanbox.model.ExecuteCodeResponse;
import com.yhh.yojcodesanbox.model.ExecuteMessage;
import com.yhh.yojcodesanbox.model.JudgeInfo;
import com.yhh.yojcodesanbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author hyh
 * @date 2024/6/5
 */
@Slf4j
public class JavaDockerCodeSandboxOld implements CodeSandbox {
    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";
    private static Boolean firstInit = true;
    private static final long TIME_OUT = 5000L;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        //  1. 把用户的代码保存为文件
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();
        List<String> inputList = executeCodeRequest.getInputList();
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        //判断全局代码目录是否存在
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        //把用户代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);


        //2. 编译代码，得到 class 文件
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsoluteFile());
        try {
            //等待程序编译，获取编译信息
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            log.info("程序编译消息：" + executeMessage);
        } catch (IOException e) {
            log.info("程序编译错误");
            return getErrorResponse(e,3);
        }


        // 3.创建容器，把编译好的用户代码复制到容器内根据多个测试用户执行用户程序，使用docker挂载目录
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        String image = "openjdk:8-alpine";

        //3.0下载镜像
        if (firstInit) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    log.info("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd
                        .exec(pullImageResultCallback)
                        .awaitCompletion();
            } catch (InterruptedException e) {
                log.info("拉取镜像异常");
                return getErrorResponse(e, 2);
            }
            firstInit = false;
        }
        log.info("镜像下载完成");

        // 3.1 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);

        // 3.2 容器配置
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);//限制内存
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);//显示CPU核数
//        hostConfig.withSecurityOpts(Arrays.asList("seccomp=安全管理配置字符串"));
        //容器挂载目录
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        log.info("容器创建信息：" + createContainerResponse);
        String containerId = createContainerResponse.getId();

        // 3.3启动容器
        dockerClient.startContainerCmd(containerId).exec();

        // docker exec keen_blackwell java -cp /app Main 1 3
        //3.4 容器执行执行用户程序的命令并获取程序输出结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();
            //以空格拆分输入用例
            String[] inputArgsArray = inputArgs.split(" ");
            //构造命令数组
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);

            //构建容器执行的命令
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();
            log.info("创建执行命令：" + execCreateCmdResponse);

            ExecuteMessage executeMessage = new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};


            // 判断是否超时
            final boolean[] timeout = {true};
            String execId = execCreateCmdResponse.getId();//标识容器执行命令的id
            //构造执行命令的回调结果
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onComplete() {
                    // 如果执行完成，则表示没超时
                    timeout[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessage[0] = new String(frame.getPayload());
                        log.info("输出错误结果：" + errorMessage[0]);
                    } else {
                        message[0] = new String(frame.getPayload());
                        log.info("输出结果：" + message[0]);
                    }
                    super.onNext(frame);
                }
            };

            final long[] maxMemory = {0L};
            // 获取程序执行时占用的最大内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    log.info("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
                }

                @Override
                public void close() throws IOException {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            });
            statsCmd.exec(statisticsResultCallback);

            //获取程序执行的时间
            long execTime = 0L;
            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MICROSECONDS);
                stopWatch.stop();
                execTime = stopWatch.getLastTaskTimeMillis();
                statsCmd.close();
            } catch (InterruptedException e) {
                log.info("程序执行异常");
                return getErrorResponse(e, 3);
            }

            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setTime(execTime);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
        }

        // 4、封装结果，跟原生实现方式完全一致
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取程序执行用时最大值，便于判断是否超时
        long maxTime = 0L;
        // 取程序执行消耗的最大内存空间
        long maxMemory = 0L;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码程序执行存在错误，状态设为3
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            Long memory = executeMessage.getMemory();
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
        }
        // 正常运行完成，状态设置为1
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);

        executeCodeResponse.setJudgeInfo(judgeInfo);

        // 5. 文件清理
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeParentPath);
            log.info("删除" + (del ? "成功" : "失败"));
        }
        return executeCodeResponse;
    }

    //6. 错误处理，提升程序健壮性
    /**
     * 获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e, Integer status ) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误，状态设为2
        executeCodeResponse.setStatus(status);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }

    public static void main(String[] args) {
        JavaDockerCodeSandboxOld javaDockerCodeSandbox = new JavaDockerCodeSandboxOld();
        String code = ResourceUtil.readStr("testcode/unsafe/Main.java", StandardCharsets.UTF_8);

        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");

        ExecuteCodeResponse executeCodeResponse = javaDockerCodeSandbox.executeCode(executeCodeRequest);
        System.out.println("executeCodeResponse = " + executeCodeResponse);


    }
}
