#!/usr/bin/env python3
"""Install the reviewed, pinned Android and Sasakiy skills for this user globally."""
from pathlib import Path
import filecmp
import shutil
import subprocess
import tempfile


def identical(source: Path, target: Path) -> bool:
    comparison = filecmp.dircmp(source, target)
    if comparison.left_only or comparison.right_only or comparison.diff_files or comparison.funny_files:
        return False
    # filecmp's default shallow comparison is insufficient for preserved user files.
    if not all(filecmp.cmp(source / f, target / f, shallow=False) for f in comparison.common_files):
        return False
    return all(identical(source / d, target / d) for d in comparison.common_dirs)


def main() -> None:
    destination = Path.home() / '.codex/skills'
    destination.mkdir(parents=True, exist_ok=True)
    sources = [
        ('android/skills', '6daa53ef27fa2aa87827d183a85c06cedceca833', ['.']),
        ('sasakiy84/agent-toolbox', 'd15780a360976d7241c517d6766bffb4af8b5e40',
         ['skills/pkg00-base', 'skills/pkg01-dev']),
    ]
    count = 0
    with tempfile.TemporaryDirectory(prefix='podcast-skills-') as temporary:
        for repository, commit, groups in sources:
            checkout = Path(temporary) / repository.split('/')[-1]
            subprocess.run(['git', 'init', '-q', str(checkout)], check=True)
            subprocess.run(['git', '-C', str(checkout), 'fetch', '-q', '--depth=1',
                            f'https://github.com/{repository}.git', commit], check=True)
            subprocess.run(['git', '-C', str(checkout), 'checkout', '-q', '--detach', 'FETCH_HEAD'], check=True)
            for group in groups:
                for skill in (checkout / group).rglob('SKILL.md'):
                    target = destination / skill.parent.name
                    if target.exists():
                        if not identical(skill.parent, target):
                            raise SystemExit(f'Preserved different existing skill: {target}; review before replacing')
                    else:
                        shutil.copytree(skill.parent, target)
                    count += 1
    print(f'Validated {count} global skills in {destination}')


if __name__ == '__main__':
    main()
