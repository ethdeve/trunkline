#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文档同步验证脚本

用于验证文档内容同步检查脚本的功能是否正常工作。
"""

import os
import sys
import tempfile
import shutil
from pathlib import Path
from typing import List, Dict, Any
import yaml


class DocsSyncValidator:
    """文档同步验证器"""
    
    def __init__(self, project_root: str = "."):
        self.project_root = Path(project_root)
        self.test_cases: List[Dict[str, Any]] = []
        self.results: List[Dict[str, Any]] = []
    
    def setup_test_cases(self):
        """设置测试用例"""
        self.test_cases = [
            {
                "name": "配置文档缺失测试",
                "description": "测试当配置文档不存在时的检查结果",
                "setup": self._setup_missing_config_doc_test,
                "expected_issues": ["MISSING_DOC"]
            },
            {
                "name": "配置不匹配测试",
                "description": "测试当文档中的配置与实际配置不符时的检查结果",
                "setup": self._setup_config_mismatch_test,
                "expected_issues": ["CONFIG_MISMATCH"]
            },
            {
                "name": "API 文档不完整测试",
                "description": "测试当 API 文档缺少端点说明时的检查结果",
                "setup": self._setup_incomplete_api_doc_test,
                "expected_issues": ["INCOMPLETE_API_DOC"]
            },
            {
                "name": "依赖文档缺失测试",
                "description": "测试当依赖文档缺少关键依赖说明时的检查结果",
                "setup": self._setup_missing_dependency_doc_test,
                "expected_issues": ["MISSING_DEPENDENCY_DOC"]
            },
            {
                "name": "正常情况测试",
                "description": "测试当所有文档都正确时的检查结果",
                "setup": self._setup_normal_case_test,
                "expected_issues": []
            }
        ]
    
    def _setup_missing_config_doc_test(self, temp_dir: Path):
        """设置配置文档缺失测试"""
        # 创建基本的项目结构，但不创建配置文档
        (temp_dir / "src/main/resources").mkdir(parents=True)
        (temp_dir / "docs/zh").mkdir(parents=True)
        
        # 创建 application.yml
        app_config = {
            "server": {"port": 8080},
            "model": {"adapter": "gpustack"}
        }
        with open(temp_dir / "src/main/resources/application.yml", 'w', encoding='utf-8') as f:
            yaml.dump(app_config, f, default_flow_style=False, allow_unicode=True)
        
        # 不创建配置文档目录
    
    def _setup_config_mismatch_test(self, temp_dir: Path):
        """设置配置不匹配测试"""
        # 创建项目结构
        (temp_dir / "src/main/resources").mkdir(parents=True)
        (temp_dir / "docs/zh/configuration").mkdir(parents=True)
        
        # 创建 application.yml，端口为 8080
        app_config = {
            "server": {"port": 8080},
            "model": {"adapter": "gpustack"}
        }
        with open(temp_dir / "src/main/resources/application.yml", 'w', encoding='utf-8') as f:
            yaml.dump(app_config, f, default_flow_style=False, allow_unicode=True)
        
        # 创建配置文档，但端口写成 9090
        config_doc = """# 应用配置

## 服务器配置

```yaml
server:
  port: 9090  # 这里故意写错端口
```

## 模型配置

```yaml
model:
  adapter: ollama  # 这里故意写错适配器
```
"""
        with open(temp_dir / "docs/zh/configuration/application-config.md", 'w', encoding='utf-8') as f:
            f.write(config_doc)
    
    def _setup_incomplete_api_doc_test(self, temp_dir: Path):
        """设置 API 文档不完整测试"""
        # 创建项目结构
        (temp_dir / "src/main/resources").mkdir(parents=True)
        (temp_dir / "docs/zh/api-reference").mkdir(parents=True)
        
        # 创建基本配置
        app_config = {
            "model": {
                "services": {
                    "chat": {"instances": []},
                    "embedding": {"instances": []}
                }
            }
        }
        with open(temp_dir / "src/main/resources/application.yml", 'w', encoding='utf-8') as f:
            yaml.dump(app_config, f, default_flow_style=False, allow_unicode=True)
        
        # 创建不完整的 API 文档（缺少一些端点）
        api_doc = """# 统一 API 文档

## 聊天 API

### POST /v1/chat/completions

聊天完成接口。

## 嵌入 API

### POST /v1/embeddings

文本嵌入接口。

# 注意：这里故意缺少其他 API 端点的说明
"""
        with open(temp_dir / "docs/zh/api-reference/universal-api.md", 'w', encoding='utf-8') as f:
            f.write(api_doc)
    
    def _setup_missing_dependency_doc_test(self, temp_dir: Path):
        """设置依赖文档缺失测试"""
        # 创建项目结构
        (temp_dir / "docs/zh/getting-started").mkdir(parents=True)
        
        # 创建 POM 文件
        pom_content = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.unreal</groupId>
    <artifactId>trunkline</artifactId>
    <version>1.0-SNAPSHOT</version>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
    </dependencies>
</project>"""
        with open(temp_dir / "pom.xml", 'w', encoding='utf-8') as f:
            f.write(pom_content)
        
        # 创建安装文档，但缺少关键依赖说明
        install_doc = """# 安装指南

## 环境要求

- Java 17+
- Maven 3.6+

## 构建项目

```bash
mvn clean package
```

# 注意：这里故意缺少依赖说明
"""
        with open(temp_dir / "docs/zh/getting-started/installation.md", 'w', encoding='utf-8') as f:
            f.write(install_doc)
    
    def _setup_normal_case_test(self, temp_dir: Path):
        """设置正常情况测试"""
        # 创建完整的项目结构
        (temp_dir / "src/main/resources").mkdir(parents=True)
        (temp_dir / "docs/zh/configuration").mkdir(parents=True)
        (temp_dir / "docs/zh/api-reference").mkdir(parents=True)
        (temp_dir / "docs/zh/getting-started").mkdir(parents=True)
        
        # 创建正确的 application.yml
        app_config = {
            "server": {"port": 8080},
            "model": {
                "adapter": "gpustack",
                "load-balance": {"type": "random"},
                "rate-limit": {"algorithm": "token-bucket"},
                "circuit-breaker": {"enabled": True},
                "services": {
                    "chat": {"instances": []},
                    "embedding": {"instances": []}
                }
            },
            "management": {
                "endpoints": {
                    "web": {
                        "exposure": {
                            "include": "health,info,metrics,prometheus"
                        }
                    }
                }
            }
        }
        with open(temp_dir / "src/main/resources/application.yml", 'w', encoding='utf-8') as f:
            yaml.dump(app_config, f, default_flow_style=False, allow_unicode=True)
        
        # 创建正确的配置文档
        config_doc = """# 应用配置

## 服务器配置

```yaml
server:
  port: 8080
```

## 模型配置

```yaml
model:
  adapter: gpustack
```
"""
        with open(temp_dir / "docs/zh/configuration/application-config.md", 'w', encoding='utf-8') as f:
            f.write(config_doc)
        
        # 创建负载均衡配置文档
        lb_doc = """# 负载均衡配置

支持的负载均衡类型：
- random
- round-robin
- least-connections
- ip-hash

哈希算法：md5
"""
        with open(temp_dir / "docs/zh/configuration/load-balancing.md", 'w', encoding='utf-8') as f:
            f.write(lb_doc)
        
        # 创建限流配置文档
        rl_doc = """# 限流配置

支持的限流算法：
- token-bucket
- leaky-bucket
- sliding-window
- warm-up

配置参数：capacity, rate, scope
"""
        with open(temp_dir / "docs/zh/configuration/rate-limiting.md", 'w', encoding='utf-8') as f:
            f.write(rl_doc)
        
        # 创建熔断器配置文档
        cb_doc = """# 熔断器配置

配置参数：
- failureThreshold
- timeout
- successThreshold
"""
        with open(temp_dir / "docs/zh/configuration/circuit-breaker.md", 'w', encoding='utf-8') as f:
            f.write(cb_doc)
        
        # 创建完整的 API 文档
        api_doc = """# 统一 API 文档

## 支持的服务

- chat
- embedding

## API 端点

- /v1/chat/completions
- /v1/embeddings
- /v1/rerank
- /v1/audio/speech
- /v1/audio/transcriptions
- /v1/images/generations
- /v1/images/edits
"""
        with open(temp_dir / "docs/zh/api-reference/universal-api.md", 'w', encoding='utf-8') as f:
            f.write(api_doc)
        
        # 创建管理 API 文档
        mgmt_doc = """# 管理 API 文档

## Actuator 端点

- health
- info
- metrics
- prometheus
"""
        with open(temp_dir / "docs/zh/api-reference/management-api.md", 'w', encoding='utf-8') as f:
            f.write(mgmt_doc)
    
    def run_test_case(self, test_case: Dict[str, Any]) -> Dict[str, Any]:
        """运行单个测试用例"""
        print(f"🧪 运行测试用例: {test_case['name']}")
        
        # 创建临时目录
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            
            # 设置测试环境
            test_case['setup'](temp_path)
            
            # 运行检查脚本
            import subprocess
            
            try:
                # 复制检查脚本到临时目录
                script_path = self.project_root / "scripts/check-docs-sync.py"
                temp_script_path = temp_path / "check-docs-sync.py"
                shutil.copy2(script_path, temp_script_path)
                
                # 运行检查
                result = subprocess.run([
                    sys.executable, str(temp_script_path),
                    "--project-root", str(temp_path),
                    "--output", str(temp_path / "report.md")
                ], capture_output=True, text=True, cwd=temp_path)
                
                # 读取报告
                report_path = temp_path / "report.md"
                report_content = ""
                if report_path.exists():
                    with open(report_path, 'r', encoding='utf-8') as f:
                        report_content = f.read()
                
                # 分析结果
                found_issues = []
                for expected_issue in test_case['expected_issues']:
                    if expected_issue in report_content:
                        found_issues.append(expected_issue)
                
                # 检查是否所有预期问题都被发现
                all_found = len(found_issues) == len(test_case['expected_issues'])
                if len(test_case['expected_issues']) == 0:
                    # 对于正常情况，检查是否没有严重问题
                    all_found = "严重问题: 0" in report_content or "所有检查都通过了" in report_content
                
                return {
                    "name": test_case['name'],
                    "passed": all_found,
                    "expected_issues": test_case['expected_issues'],
                    "found_issues": found_issues,
                    "report": report_content,
                    "exit_code": result.returncode,
                    "stdout": result.stdout,
                    "stderr": result.stderr
                }
                
            except Exception as e:
                return {
                    "name": test_case['name'],
                    "passed": False,
                    "error": str(e),
                    "expected_issues": test_case['expected_issues'],
                    "found_issues": []
                }
    
    def run_all_tests(self) -> bool:
        """运行所有测试用例"""
        print("🚀 开始验证文档同步检查功能...")
        
        self.setup_test_cases()
        
        all_passed = True
        
        for test_case in self.test_cases:
            result = self.run_test_case(test_case)
            self.results.append(result)
            
            if result['passed']:
                print(f"✅ {result['name']} - 通过")
            else:
                print(f"❌ {result['name']} - 失败")
                all_passed = False
        
        return all_passed
    
    def generate_validation_report(self) -> str:
        """生成验证报告"""
        report = []
        report.append("# 文档同步检查验证报告\n")
        
        total_tests = len(self.results)
        passed_tests = len([r for r in self.results if r['passed']])
        failed_tests = total_tests - passed_tests
        
        report.append(f"## 验证统计\n")
        report.append(f"- 总测试数: {total_tests}")
        report.append(f"- 通过测试: {passed_tests}")
        report.append(f"- 失败测试: {failed_tests}")
        report.append(f"- 通过率: {(passed_tests/total_tests*100):.1f}%\n")
        
        if failed_tests == 0:
            report.append("🎉 所有验证测试都通过了！文档同步检查功能正常工作。\n")
        else:
            report.append("⚠️ 部分验证测试失败，请检查文档同步检查功能。\n")
        
        # 详细测试结果
        report.append("## 详细测试结果\n")
        
        for result in self.results:
            status = "✅ 通过" if result['passed'] else "❌ 失败"
            report.append(f"### {result['name']} - {status}")
            
            if 'error' in result:
                report.append(f"**错误**: {result['error']}")
            else:
                report.append(f"**预期问题**: {', '.join(result['expected_issues']) if result['expected_issues'] else '无'}")
                report.append(f"**发现问题**: {', '.join(result['found_issues']) if result['found_issues'] else '无'}")
                
                if not result['passed']:
                    report.append("**详细信息**:")
                    if result.get('stdout'):
                        report.append(f"- 标准输出: {result['stdout'][:200]}...")
                    if result.get('stderr'):
                        report.append(f"- 错误输出: {result['stderr'][:200]}...")
            
            report.append("")
        
        return "\n".join(report)


def main():
    """主函数"""
    import argparse
    
    parser = argparse.ArgumentParser(description="文档同步检查验证工具")
    parser.add_argument("--project-root", default=".", help="项目根目录路径")
    parser.add_argument("--output", help="输出验证报告文件路径")
    
    args = parser.parse_args()
    
    # 创建验证器并运行验证
    validator = DocsSyncValidator(args.project_root)
    success = validator.run_all_tests()
    
    # 生成验证报告
    report = validator.generate_validation_report()
    
    if args.output:
        with open(args.output, 'w', encoding='utf-8') as f:
            f.write(report)
        print(f"📄 验证报告已保存到: {args.output}")
    else:
        print("\n" + report)
    
    # 根据验证结果设置退出码
    if success:
        print("✅ 所有验证测试通过，文档同步检查功能正常")
        sys.exit(0)
    else:
        print("❌ 部分验证测试失败，请检查文档同步检查功能")
        sys.exit(1)


if __name__ == "__main__":
    main()