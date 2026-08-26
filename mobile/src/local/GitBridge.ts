import { permissionDetector } from './PermissionDetector';
import { NativeModules } from 'react-native';

const { PrivilegedExecution } = NativeModules;

export interface GitStatus {
  files: GitFileStatus[];
  currentBranch: string;
  aheadCount: number;
  behindCount: number;
}

export interface GitFileStatus {
  path: string;
  status: 'added' | 'modified' | 'deleted' | 'untracked' | 'renamed';
  oldPath?: string;
}

export interface GitCommit {
  hash: string;
  message: string;
  author: string;
  date: number;
}

export interface GitBranch {
  name: string;
  isCurrent: boolean;
  isRemote: boolean;
}

class GitBridge {
  private get isPrivileged(): boolean {
    return permissionDetector.isPrivileged() && !!PrivilegedExecution;
  }

  async getStatus(path: string): Promise<GitStatus> {
    if (this.isPrivileged) {
      return this.nativeGitStatus(path);
    }
    return this.sandboxGitStatus(path);
  }

  async stageFiles(path: string, files: string[]): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitStage(path, files);
      return;
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async commit(path: string, message: string): Promise<string> {
    if (this.isPrivileged) {
      return this.nativeGitCommit(path, message);
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async push(path: string, remote: string = 'origin', branch?: string): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitPush(path, remote, branch);
      return;
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async pull(path: string, remote: string = 'origin', branch?: string): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitPull(path, remote, branch);
      return;
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async listBranches(path: string): Promise<GitBranch[]> {
    if (this.isPrivileged) {
      return this.nativeGitBranches(path);
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async switchBranch(path: string, name: string): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitSwitch(path, name);
      return;
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async getDiff(path: string, file?: string): Promise<string> {
    if (this.isPrivileged) {
      return this.nativeGitDiff(path, file);
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  async getLog(path: string, maxCount: number = 20): Promise<GitCommit[]> {
    if (this.isPrivileged) {
      return this.nativeGitLog(path, maxCount);
    }
    throw new Error('Git operations require privileged mode with Alpine Linux installed');
  }

  private async sandboxGitStatus(path: string): Promise<GitStatus> {
    return {
      files: [],
      currentBranch: 'main',
      aheadCount: 0,
      behindCount: 0,
    };
  }

  private async nativeGitStatus(path: string): Promise<GitStatus> {
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git status --porcelain -b 2>/dev/null || echo ""`
    );
    const lines = result.stdout.trim().split('\n').filter(Boolean);
    const branchLine = lines[0] || '';
    const currentBranch = branchLine.replace('## ', '').split('...')[0] || 'main';
    const files: GitFileStatus[] = [];

    for (let i = 1; i < lines.length; i++) {
      const line = lines[i];
      const statusCode = line.substring(0, 2);
      const filepath = line.substring(3).trim();
      const status = this.parseGitStatus(statusCode);
      files.push({ path: filepath, status });
    }

    return { files, currentBranch, aheadCount: 0, behindCount: 0 };
  }

  private async nativeGitStage(path: string, files: string[]): Promise<void> {
    const fileList = files.map((f: string) => `'${f}'`).join(' ');
    await PrivilegedExecution.execAlpineCommand(`cd '${path}' && git add ${fileList}`);
  }

  private async nativeGitCommit(path: string, message: string): Promise<string> {
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git commit -m '${message.replace(/'/g, "\\'")}'`
    );
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git rev-parse HEAD`
    );
    return result.stdout.trim();
  }

  private async nativeGitPush(path: string, remote: string, branch?: string): Promise<void> {
    const branchArg = branch ? ` ${branch}` : '';
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git push ${remote}${branchArg}`
    );
  }

  private async nativeGitPull(path: string, remote: string, branch?: string): Promise<void> {
    const branchArg = branch ? ` ${branch}` : '';
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git pull ${remote}${branchArg}`
    );
  }

  private async nativeGitBranches(path: string): Promise<GitBranch[]> {
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git branch -a`
    );
    return result.stdout.trim().split('\n').filter(Boolean).map((line: string) => ({
      name: line.replace('*', '').trim(),
      isCurrent: line.startsWith('*'),
      isRemote: line.includes('remotes/'),
    }));
  }

  private async nativeGitSwitch(path: string, name: string): Promise<void> {
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git checkout '${name}'`
    );
  }

  private async nativeGitDiff(path: string, file?: string): Promise<string> {
    const fileArg = file ? ` '${file}'` : '';
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git diff${fileArg}`
    );
    return result.stdout;
  }

  private async nativeGitLog(path: string, maxCount: number): Promise<GitCommit[]> {
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git log --max-count=${maxCount} --format='%H|%s|%an|%at'`
    );
    return result.stdout.trim().split('\n').filter(Boolean).map((line: string) => {
      const [hash, message, author, date] = line.split('|');
      return { hash, message, author, date: parseInt(date) * 1000 };
    });
  }

  private parseGitStatus(code: string): GitFileStatus['status'] {
    const trimmed = code.trim();
    if (trimmed.includes('A')) return 'added';
    if (trimmed.includes('M')) return 'modified';
    if (trimmed.includes('D')) return 'deleted';
    if (trimmed.includes('R')) return 'renamed';
    if (trimmed.includes('?')) return 'untracked';
    return 'modified';
  }
}

export const gitBridge = new GitBridge();
export default GitBridge;