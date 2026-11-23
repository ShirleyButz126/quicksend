// TCB DISABLED: QuickSend is a pure local LAN file transfer app.
// No cloud analytics needed. All functions are no-ops.
// TCB SDK request to tcb-api.tencentcloudapi.com caused browser errors.

export const initTCB = async () => { /* no-op */ };
export const getDB = () => null;
export const recordInstall = async () => { /* no-op */ };
export const recordDailyActive = async () => { /* no-op */ };
export const recordFileStats = async (_type: 'upload' | 'download', _count: number, _size: number) => { /* no-op */ };
