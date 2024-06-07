package com.yhh.yojcodesanbox;

import com.yhh.yojcodesanbox.model.ExecuteCodeRequest;
import com.yhh.yojcodesanbox.model.ExecuteCodeResponse;

/**
 * 代码沙箱接口
 *
 * @author hyh
 * @date 2024/6/5
 */
public interface CodeSandbox {
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);

}
