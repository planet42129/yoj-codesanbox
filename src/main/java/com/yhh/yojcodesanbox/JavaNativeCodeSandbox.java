package com.yhh.yojcodesanbox;


import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.yhh.yojcodesanbox.model.ExecuteCodeRequest;
import com.yhh.yojcodesanbox.model.ExecuteCodeResponse;
import com.yhh.yojcodesanbox.model.ExecuteMessage;
import com.yhh.yojcodesanbox.model.JudgeInfo;
import com.yhh.yojcodesanbox.security.DefaultSecurityManager;
import com.yhh.yojcodesanbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author hyh
 * @date 2024/6/5
 */
@Slf4j
public class JavaNativeCodeSandbox implements CodeSandbox {
    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";
    private static final long DEFAULT_TIME_OUT = 10000L;


    //安全防护3：黑名单校验
    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final WordTree WORD_TREE;
    static {
        WORD_TREE = new WordTree();
        WORD_TREE.addWords(blackList);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.setSecurityManager(new DefaultSecurityManager());

//        1. 把用户的代码保存为文件
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

        FoundWord foundWord = WORD_TREE.matchWord(code);
        if (foundWord != null) {
            log.info("程序包含敏感词：" + foundWord.getFoundWord());
            return getErrorResponse(new RuntimeException("程序包含敏感词：" + foundWord.getFoundWord()));
        }


        //2. 编译代码，得到 class 文件
//        String compileCmd = String.format("javac -encoding utf-8 %s", userCodePath);
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsoluteFile());
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        try {
            //等待程序编译，获取错误码
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println("executeMessage = " + executeMessage);
        } catch (Exception e) {
            return getErrorResponse(e);
        }
        // 3. 执行代码，得到输出结果
        for (String inputArgs :
                inputList) {
            //安全防护2：最大内存限制 在执行代码的命令：java Xmx256m
            String executeCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(executeCmd);
                // 安全防护 1：超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(DEFAULT_TIME_OUT);
                        log.info("程序执行超时");
                        runProcess.destroy();
                        //todo 再判断一下runProcess是否执行完毕
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
//                ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(runProcess, inputArgs);
                executeMessageList.add(executeMessage);
                System.out.println("executeMessage = " + executeMessage);
            } catch (Exception e) {
                return getErrorResponse(e);
            }
        }

//        4. 收集整理输出结果
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码执行中存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
//        5. 文件清理，释放空间
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
        }
        return executeCodeResponse;
    }

    //        6. 错误处理，提升程序健壮性
    /**
     * 获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }

    public static void main(String[] args) {
        JavaNativeCodeSandbox javaNativeCodeSandbox = new JavaNativeCodeSandbox();
        String code = ResourceUtil.readStr("testcode/unsafe/Main.java", StandardCharsets.UTF_8);


        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");

        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println("executeCodeResponse = " + executeCodeResponse);


    }
}
