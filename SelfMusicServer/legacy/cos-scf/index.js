'use strict';

const crypto = require('crypto');
const COS = require('cos-nodejs-sdk-v5');

const bucket = process.env.COS_BUCKET || 'selfmusic-1477805312';
const region = process.env.COS_REGION || 'ap-guangzhou';
const apiToken = process.env.API_TOKEN;
const userId = process.env.SELFMUSIC_USER_ID || 'owner';
const indexKey = `users/${userId}/_index/songs.json`;
const cos = new COS({ SecretId: process.env.TENCENT_SECRET_ID, SecretKey: process.env.TENCENT_SECRET_KEY });

function response(statusCode, body) {
  return { statusCode, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }, body: JSON.stringify(body) };
}

function call(method, params) {
  return new Promise((resolve, reject) => cos[method](params, (err, data) => err ? reject(err) : resolve(data)));
}

async function readSongs() {
  try {
    const data = await call('getObject', { Bucket: bucket, Region: region, Key: indexKey });
    return JSON.parse(Buffer.from(data.Body).toString('utf8'));
  } catch (error) {
    if (error.statusCode === 404 || error.code === 'NoSuchKey') return [];
    throw error;
  }
}

async function writeSongs(songs) {
  await call('putObject', {
    Bucket: bucket, Region: region, Key: indexKey,
    Body: Buffer.from(JSON.stringify(songs, null, 2)), ContentType: 'application/json'
  });
}

function signedUrl(key, method, expiresSeconds) {
  return new Promise((resolve, reject) => cos.getObjectUrl({
    Bucket: bucket, Region: region, Key: key, Sign: true,
    Method: method, Expires: expiresSeconds, Protocol: 'https:'
  }, (err, data) => err ? reject(err) : resolve(data.Url)));
}

function cleanName(value) {
  return String(value || '').replace(/[\\/\0]/g, '').trim().slice(0, 180);
}

function extension(name, fallback) {
  const match = cleanName(name).toLowerCase().match(/\.([a-z0-9]{1,8})$/);
  return match ? match[1] : fallback;
}

exports.main_handler = async (event) => {
  try {
    const headers = event.headers || {};
    const authorization = headers.authorization || headers.Authorization || '';
    if (!apiToken || authorization !== `Bearer ${apiToken}`) return response(401, { error: 'unauthorized' });

    const method = String(event.httpMethod || event.requestContext?.http?.method || 'GET').toUpperCase();
    const path = String(event.path || event.rawPath || '/').replace(/\/$/, '') || '/';
    const body = event.body ? JSON.parse(event.isBase64Encoded ? Buffer.from(event.body, 'base64').toString() : event.body) : {};

    if (method === 'GET' && path.endsWith('/v1/songs')) {
      const songs = await readSongs();
      return response(200, { items: songs.filter(x => !x.deletedAt).sort((a, b) => b.createdAt - a.createdAt) });
    }

    const playMatch = path.match(/\/v1\/songs\/([^/]+)\/play-url$/);
    if (method === 'GET' && playMatch) {
      const song = (await readSongs()).find(x => x.id === playMatch[1] && !x.deletedAt);
      if (!song) return response(404, { error: 'song_not_found' });
      return response(200, { url: await signedUrl(song.audioObjectKey, 'GET', 14400), expiresIn: 14400 });
    }

    if (method === 'POST' && path.endsWith('/v1/uploads')) {
      const id = crypto.randomUUID();
      const prefix = `users/${userId}`;
      const audioObjectKey = `${prefix}/audio/${id}/original.${extension(body.audioName, 'mp3')}`;
      const coverObjectKey = body.coverName ? `${prefix}/covers/${id}/cover.${extension(body.coverName, 'webp')}` : null;
      const lyricObjectKey = body.lyricName ? `${prefix}/lyrics/${id}/lyrics.${extension(body.lyricName, 'lrc')}` : null;
      const makeUpload = async (key, contentType) => key ? ({ key, url: await signedUrl(key, 'PUT', 1800), contentType }) : null;
      return response(200, {
        uploadId: id,
        audio: await makeUpload(audioObjectKey, body.audioContentType || 'application/octet-stream'),
        cover: await makeUpload(coverObjectKey, body.coverContentType || 'application/octet-stream'),
        lyric: await makeUpload(lyricObjectKey, body.lyricContentType || 'text/plain')
      });
    }

    const completeMatch = path.match(/\/v1\/uploads\/([^/]+)\/complete$/);
    if (method === 'POST' && completeMatch) {
      const id = completeMatch[1];
      const expectedPrefix = `users/${userId}/audio/${id}/`;
      if (!String(body.audioObjectKey || '').startsWith(expectedPrefix)) return response(400, { error: 'invalid_object_key' });
      const head = await call('headObject', { Bucket: bucket, Region: region, Key: body.audioObjectKey });
      const songs = await readSongs();
      const song = {
        id, title: cleanName(body.title) || '未命名歌曲', artist: cleanName(body.artist) || '云端音乐',
        album: cleanName(body.album) || '我的云盘', durationMs: Number(body.durationMs || 0),
        mimeType: body.mimeType || 'audio/mpeg', fileSize: Number(head.headers?.['content-length'] || body.fileSize || 0),
        audioObjectKey: body.audioObjectKey, coverObjectKey: body.coverObjectKey || null,
        lyricObjectKey: body.lyricObjectKey || null, version: String(head.headers?.etag || Date.now()).replace(/"/g, ''),
        createdAt: Date.now(), updatedAt: Date.now()
      };
      await writeSongs([...songs.filter(x => x.id !== id), song]);
      return response(200, song);
    }

    return response(404, { error: 'not_found' });
  } catch (error) {
    console.error(error);
    return response(500, { error: 'internal_error', requestId: event.requestContext?.requestId });
  }
};
