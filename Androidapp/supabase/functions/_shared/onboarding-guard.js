export async function readOnboardingBody(req, maxBytes = 8192, timeoutMs = 5000) {
  const reader = req.body?.getReader();
  if (!reader) throw new Error('missing-body');
  let timer;
  const deadline = new Promise((_, reject) => {timer=setTimeout(()=>{void reader.cancel().catch(()=>{});reject(new Error('body-timeout'));},timeoutMs);});
  const read = (async()=>{const chunks=[];let size=0;
    while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>maxBytes){void reader.cancel().catch(()=>{});throw new Error('body-too-large');}chunks.push(value);}
    const bytes=new Uint8Array(size);let at=0;for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.byteLength;}
    const value=JSON.parse(new TextDecoder().decode(bytes));if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('invalid-body');return value;
  })();
  try{return await Promise.race([read,deadline]);}finally{clearTimeout(timer);reader.releaseLock();}
}
