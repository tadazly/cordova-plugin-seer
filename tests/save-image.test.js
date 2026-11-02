const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function load(resolveLocalFileSystemURL, nativeResult) {
    const calls = [];
    const context = {
        module: { exports: {} }, Promise, resolveLocalFileSystemURL,
        require: () => (success, failure, service, action, args) => {
            calls.push({ service, action, args });
            if (nativeResult) return nativeResult(success, failure, action, args);
            if (args?.[0] === '/denied.jpg') failure({ code: 'PERMISSION_DENIED', message: 'denied' });
            else success({ uri: 'content://media/1', fileName: 'test.jpg', mimeType: 'image/jpeg' });
        }
    };
    vm.runInNewContext(fs.readFileSync(require.resolve('../www/CordovaSeer.js'), 'utf8'), context);
    return { plugin: context.module.exports, calls };
}

test('PNG/JPEG dataURL 和本地路径通过同一个 Cordova action 原样传入', async () => {
    const { plugin, calls } = load();
    for (const source of ['data:image/png;base64,aGVsbG8=', 'data:image/jpeg;base64,aGVsbG8=', '/tmp/a.jpg', 'file:///tmp/a.png', 'content://media/1']) {
        const result = await plugin.saveImageToAlbum(source, { fileName: '攻略.png' });
        assert.equal(result.uri, 'content://media/1');
        assert.deepEqual(JSON.parse(JSON.stringify(calls.at(-1))), {
            service: 'CordovaSeer', action: 'saveImageToAlbum', args: [source, '攻略.png']
        });
    }
});

test('无效参数和远程 URL 不进入原生层', async () => {
    const { plugin, calls } = load();
    for (const source of [null, 42, '', 'https://example.com/x.jpg', 'data:text/plain;base64,eA==']) {
        await assert.rejects(plugin.saveImageToAlbum(source), error => error.code === 'INVALID_ARGUMENT');
    }
    for (const options of [null, [], { fileName: '../x' }, { fileName: '\u0000x' }, { fileName: '...' }, { fileName: ' ' }]) {
        await assert.rejects(plugin.saveImageToAlbum('/tmp/x.jpg', options), error => error.code === 'INVALID_ARGUMENT');
    }
    assert.equal(calls.length, 0);
});

test('FileEntry 地址先通过 File 插件转换，保留带空格的 nativeURL', async () => {
    const { plugin, calls } = load((url, success) => success({ isFile: true, nativeURL: 'file:///tmp/a%20b.jpg' }));
    await plugin.saveImageToAlbum('cdvfile://localhost/temporary/a%20b.jpg');
    assert.equal(calls[0].args[0], 'file:///tmp/a%20b.jpg');
    await plugin.saveImageToAlbum('https://localhost/__cdvfile_temporary__/x.jpg');
    assert.equal(calls.length, 2);
});

test('File 插件缺失、文件不存在和权限拒绝均返回结构化错误', async () => {
    await assert.rejects(load().plugin.saveImageToAlbum('cdvfile://localhost/temporary/x.jpg'), error => error.code === 'INVALID_SOURCE');
    await assert.rejects(load((url, success, fail) => fail()).plugin.saveImageToAlbum('cdvfile://localhost/temporary/x.jpg'), error => error.code === 'FILE_NOT_FOUND');
    await assert.rejects(load().plugin.saveImageToAlbum('/denied.jpg'), error => error.code === 'PERMISSION_DENIED');
});

test('原有安全区接口仍可调用', async () => {
    const { plugin, calls } = load();
    await plugin.getSafeAreaXywh();
    assert.equal(calls[0].action, 'getSafeAreaXywh');
});

test('权限查询返回原生状态，不调用保存或申请权限 action', async () => {
    for (const status of ['notRequired', 'granted', 'notDetermined', 'denied', 'restricted', 'unavailable', 'unknown']) {
        const value = { status, canSave: status === 'notRequired' || status === 'granted', canRequest: status === 'notDetermined' || status === 'unknown' };
        const { plugin, calls } = load(undefined, success => success(value));
        assert.deepEqual(await plugin.getPhotoAlbumPermissionStatus(), value);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].action, 'getPhotoAlbumPermissionStatus');
        assert.equal(calls[0].args.length, 0);
    }
});

test('查询不缓存授权结果，从设置返回能读取新状态', async () => {
    let status = 'denied';
    const { plugin, calls } = load(undefined, success => success({ status }));
    assert.equal((await plugin.getPhotoAlbumPermissionStatus()).status, 'denied');
    status = 'granted';
    assert.equal((await plugin.getPhotoAlbumPermissionStatus()).status, 'granted');
    assert.equal(calls.length, 2);
});

test('打开设置返回 void，并保留查询和打开失败的原生错误', async () => {
    const { plugin, calls } = load();
    assert.equal(await plugin.openAppSettings(), undefined);
    assert.equal(calls[0].action, 'openAppSettings');
    const denied = load(undefined, (success, failure) => failure({ code: 'OPEN_SETTINGS_FAILED', message: 'failed' })).plugin;
    await assert.rejects(denied.openAppSettings(), error => error.code === 'OPEN_SETTINGS_FAILED');
    const failed = load(undefined, (success, failure) => failure({ code: 'SAVE_FAILED', message: 'query failed' })).plugin;
    await assert.rejects(failed.getPhotoAlbumPermissionStatus(), error => error.code === 'SAVE_FAILED');
});
