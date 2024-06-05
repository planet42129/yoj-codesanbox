package com.yhh.yoj.judge.codesandbox;

import com.yhh.yoj.judge.codesandbox.model.ExecuteCodeRequest;
import com.yhh.yoj.judge.codesandbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱接口
 *
 * @author hyh
 * @date 2024/6/5
 */
public interface CodeSandbox {
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);

}
