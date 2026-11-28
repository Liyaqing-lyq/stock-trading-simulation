"""旧本机报告路径，使用仓内审计器；优先沿用调用方已经载入的冻结审计目录。"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
workspace_scripts = Path(__file__).resolve().parents[3] / "scripts"
sys.path.append(str(workspace_scripts))
from audit_ten_company_market import main, report

if __name__ == "__main__":
    main()
