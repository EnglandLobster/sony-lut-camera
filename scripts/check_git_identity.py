#!/usr/bin/env python3
"""Require an explicit repository-local public identity before maintainer writes."""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True, stderr=subprocess.DEVNULL).strip()


def check():
    try:
        name = git('config', '--local', '--get', 'user.name')
        email = git('config', '--local', '--get', 'user.email')
        if not name or not re.fullmatch(r'[^\s<>]+@users\.noreply\.github\.com', email):
            raise ValueError()
        expected = name + ' <' + email + '>'
        for key in ['GIT_AUTHOR_IDENT', 'GIT_COMMITTER_IDENT']:
            if git('var', key).split('> ', 1)[0] + '>' != expected:
                raise ValueError()
    except (subprocess.CalledProcessError, ValueError):
        raise SystemExit('Git identity check failed: configure repository-local user.name and GitHub noreply user.email; remove conflicting identity overrides. No identity values were printed.')


if __name__ == '__main__':
    check()
    print('Git identity verified: explicit repository-local GitHub privacy identity.')
