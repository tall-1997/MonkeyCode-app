import { permissionDetector } from './PermissionDetector';
import * as isomorphicGit from 'isomorphic-git';
import http from 'isomorphic-git/http/web';
import * as ExpoFileSystem from 'expo-file-system';
import { Platform } from 'react-native';

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
  private fs: typeof ExpoFileSystem;

  constructor() {
    this.fs = ExpoFileSystem;
  }

  private get isPrivileged(): boolean {
    return permissionDetector.isPrivileged();
  }

  async getStatus(path: string): Promise<GitStatus> {
    if (this.isPrivileged) {
      return this.nativeGitStatus(path);
    }
    return this.isomorphicGitStatus(path);
  }

  async stageFiles(path: string, files: string[]): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitStage(path, files);
      return;
    }
    for (const file of files) {
      await isomorphicGit.add({ fs: this.fs, dir: path, filepath: file });
    }
  }

  async commit(path: string, message: string): Promise<string> {
    if (this.isPrivileged) {
      return this.nativeGitCommit(path, message);
    }
    return isomorphicGit.commit({
      fs: this.fs,
      dir: path,
      message,
      author: { name: 'MonkeyCode', email: 'mobile@monkeycode.ai' },
    });
  }

  async push(path: string, remote: string = 'origin', branch?: string): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitPush(path, remote, branch);
      return;
    }
    await isomorphicGit.push({
      fs: this.fs,
      http,
      dir: path,
      remote,
      ref: branch || 'HEAD',
    });
  }

  async pull(path: string, remote: string = 'origin', branch?: string): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitPull(path, remote, branch);
      return;
    }
    await isomorphicGit.pull({
      fs: this.fs,
      http,
      dir: path,
      ref: branch || 'HEAD',
      singleBranch: true,
    });
  }

  async listBranches(path: string): Promise<GitBranch[]> {
    if (this.isPrivileged) {
      return this.nativeGitBranches(path);
    }
    const branches = await isomorphicGit.listBranches({ fs: this.fs, dir: path });
    const currentBranch = await isomorphicGit.currentBranch({ fs: this.fs, dir: path });
    return branches.map((name) => ({
      name,
      isCurrent: name === currentBranch,
      isRemote: name.startsWith('remotes/'),
    }));
  }

  async switchBranch(path: string, name: string): Promise<void> {
    if (this.isPrivileged) {
      await this.nativeGitSwitch(path, name);
      return;
    }
    await isomorphicGit.checkout({ fs: this.fs, dir: path, ref: name });
  }

  async getDiff(path: string, file?: string): Promise<string> {
    // 特权模式暂不支持 native diff，使用 isomorphic-git 兜底
    return this.isomorphicGitDiff(path, file);
  }

  async getLog(path: string, maxCount: number = 20): Promise<GitCommit[]> {
    if (this.isPrivileged) {
      return this.nativeGitLog(path, maxCount);
    }
    const commits = await isomorphicGit.log({ fs: this.fs, dir: path, depth: maxCount });
    return commits.map((c) => ({
      hash: c.oid,
      message: c.commit.message,
      author: c.commit.author.name,
      date: c.commit.author.timestamp * 1000,
    }));
  }

  // ==================== isomorphic-git implementations ====================

  private async isomorphicGitStatus(dir: string): Promise<GitStatus> {
    const statusMatrix = await isomorphicGit.statusMatrix({ fs: this.fs, dir });
    const currentBranch = await isomorphicGit.currentBranch({ fs: this.fs, dir })
      .catch(() => 'HEAD');

    const files: GitFileStatus[] = [];
    for (const [filepath, head, workdir, stage] of statusMatrix) {
      const status = this.mapStatus(head, workdir, stage);
      if (status !== 'unmodified') {
        files.push({ path: filepath, status });
      }
    }

    return {
      files,
      currentBranch: currentBranch || 'HEAD',
      aheadCount: 0,
      behindCount: 0,
    };
  }

  private mapStatus(head: number, workdir: number, stage: number): GitFileStatus['status'] | 'unmodified' {
    if (head === 1 && workdir === 0 && stage === 1) return 'deleted';
    if (head === 1 && workdir === 2 && stage === 1) return 'modified';
    if (head === 0 && workdir === 2 && stage === 0) return 'untracked';
    if (head === 0 && workdir === 2 && stage === 2) return 'added';
    return 'unmodified';
  }

  private async isomorphicGitDiff(dir: string, file?: string): Promise<string> {
    // isomorphic-git diff is complex; return basic status for now
    const status = await this.isomorphicGitStatus(dir);
    return status.files.map((f) => `${f.status}: ${f.path}`).join('\n');
  }

  // ==================== Native Git implementations (via Alpine Linux) ====================

  private async nativeGitStatus(path: string): Promise<GitStatus> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git status --porcelain -b`
    );
    const lines = result.stdout.trim().split('\n').filter(Boolean);
    const branchLine = lines[0];
    const currentBranch = branchLine.replace('## ', '').split('...')[0];
    const files: GitFileStatus[] = [];

    for (let i = 1; i < lines.length; i++) {
      const line = lines[i];
      const statusCode = line.substring(0, 2);
      const filepath = line.substring(3);
      const status = this.parseGitStatus(statusCode);
      files.push({ path: filepath, status });
    }

    return { files, currentBranch, aheadCount: 0, behindCount: 0 };
  }

  private parseGitStatus(code: string): GitFileStatus['status'] {
    if (code.includes('A')) return 'added';
    if (code.includes('M')) return 'modified';
    if (code.includes('D')) return 'deleted';
    if (code.includes('R')) return 'renamed';
    if (code.includes('?')) return 'untracked';
    return 'modified';
  }

  private async nativeGitStage(path: string, files: string[]): Promise<void> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    const fileList = files.map((f) => `'${f}'`).join(' ');
    await PrivilegedExecution.execAlpineCommand(`cd '${path}' && git add ${fileList}`);
  }

  private async nativeGitCommit(path: string, message: string): Promise<string> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git commit -m '${message.replace(/'/g, "\\'")}'`
    );
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git rev-parse HEAD`
    );
    return result.stdout.trim();
  }

  private async nativeGitPush(path: string, remote: string, branch?: string): Promise<void> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    const branchArg = branch ? ` ${branch}` : '';
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git push ${remote}${branchArg}`
    );
  }

  private async nativeGitPull(path: string, remote: string, branch?: string): Promise<void> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    const branchArg = branch ? ` ${branch}` : '';
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git pull ${remote}${branchArg}`
    );
  }

  private async nativeGitBranches(path: string): Promise<GitBranch[]> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git branch -a`
    );
    return result.stdout.trim().split('\n').filter(Boolean).map((line) => ({
      name: line.replace('*', '').trim(),
      isCurrent: line.startsWith('*'),
      isRemote: line.includes('remotes/'),
    }));
  }

  private async nativeGitSwitch(path: string, name: string): Promise<void> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git checkout '${name}'`
    );
  }

  private async nativeGitLog(path: string, maxCount: number): Promise<GitCommit[]> {
    const { PrivilegedExecution } = require('react-native').NativeModules;
    const result = await PrivilegedExecution.execAlpineCommand(
      `cd '${path}' && git log --max-count=${maxCount} --format='%H|%s|%an|%at'`
    );
    return result.stdout.trim().split('\n').filter(Boolean).map((line) => {
      const [hash, message, author, date] = line.split('|');
      return { hash, message, author, date: parseInt(date) * 1000 };
    });
  }
}

export const gitBridge = new GitBridge();
export default GitBridge;