// AcroNet Crypto Engine — Pure JS AES-256-GCM
// Wire-compatible with GhostCrypto.kt: Base64(IV[12] || ciphertext || tag[16])
var aesKeyBytes=null;
var SB=[99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,152,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22];

function aesKeyExp(k){
  var w=new Array(60),rc=[1,2,4,8,16,32,64,128,27,54];
  for(var i=0;i<8;i++)w[i]=(k[4*i]<<24)|(k[4*i+1]<<16)|(k[4*i+2]<<8)|k[4*i+3];
  for(var i=8;i<60;i++){
    var t=w[i-1];
    if(i%8===0){t=((SB[(t>>16)&255]<<24)|(SB[(t>>8)&255]<<16)|(SB[t&255]<<8)|SB[(t>>24)&255])^(rc[i/8-1]<<24);}
    else if(i%8===4){t=(SB[(t>>24)&255]<<24)|(SB[(t>>16)&255]<<16)|(SB[(t>>8)&255]<<8)|SB[t&255];}
    w[i]=(w[i-8]^t)>>>0;
  }
  return w;
}

function aesBlock(s,w){
  var t=new Uint8Array(16);for(var i=0;i<16;i++)t[i]=s[i];
  for(var c=0;c<4;c++){t[4*c]^=(w[c]>>>24)&255;t[4*c+1]^=(w[c]>>>16)&255;t[4*c+2]^=(w[c]>>>8)&255;t[4*c+3]^=w[c]&255;}
  for(var r=1;r<=14;r++){
    var n=new Uint8Array(16);for(var i=0;i<16;i++)n[i]=SB[t[i]];
    var u=new Uint8Array(16);
    u[0]=n[0];u[1]=n[5];u[2]=n[10];u[3]=n[15];u[4]=n[4];u[5]=n[9];u[6]=n[14];u[7]=n[3];
    u[8]=n[8];u[9]=n[13];u[10]=n[2];u[11]=n[7];u[12]=n[12];u[13]=n[1];u[14]=n[6];u[15]=n[11];
    if(r<14){
      var m=new Uint8Array(16);
      for(var c=0;c<4;c++){
        var a=u[4*c],b=u[4*c+1],d=u[4*c+2],e=u[4*c+3];
        var a2=((a<<1)^(a&128?27:0))&255,b2=((b<<1)^(b&128?27:0))&255;
        var d2=((d<<1)^(d&128?27:0))&255,e2=((e<<1)^(e&128?27:0))&255;
        m[4*c]=a2^b2^b^d^e;m[4*c+1]=a^b2^d2^d^e;m[4*c+2]=a^b^d2^e2^e;m[4*c+3]=a2^a^b^d^e2;
      }
      u=m;
    }
    for(var c=0;c<4;c++){var ki=4*r+c;u[4*c]^=(w[ki]>>>24)&255;u[4*c+1]^=(w[ki]>>>16)&255;u[4*c+2]^=(w[ki]>>>8)&255;u[4*c+3]^=w[ki]&255;}
    t=u;
  }
  return t;
}

function gfMul(x,y){
  var z=new Uint8Array(16),v=new Uint8Array(y);
  for(var i=0;i<128;i++){
    if(x[i>>>3]&(128>>>(i&7)))for(var j=0;j<16;j++)z[j]^=v[j];
    var lsb=v[15]&1;
    for(var j=15;j>0;j--)v[j]=(v[j]>>>1)|((v[j-1]&1)<<7);
    v[0]>>>=1;if(lsb)v[0]^=0xe1;
  }
  return z;
}

function gcmEnc(pt,keyBytes,iv){
  var w=aesKeyExp(keyBytes);
  var H=aesBlock(new Uint8Array(16),w);
  var J=new Uint8Array(16);J.set(iv);J[15]=1;
  var ct=new Uint8Array(pt.length);
  var ctr=new Uint8Array(J);
  for(var i=0;i<pt.length;i+=16){
    ctr[15]++;if(ctr[15]===0){ctr[14]++;if(ctr[14]===0)ctr[13]++;}
    var ks=aesBlock(ctr,w);
    var end=Math.min(16,pt.length-i);
    for(var j=0;j<end;j++)ct[i+j]=pt[i+j]^ks[j];
  }
  // GHASH over ciphertext (no AAD)
  var gh=new Uint8Array(16);
  for(var i=0;i<ct.length;i+=16){
    var blk=new Uint8Array(16);
    var end=Math.min(16,ct.length-i);
    for(var j=0;j<end;j++)blk[j]=ct[i+j];
    for(var j=0;j<16;j++)gh[j]^=blk[j];
    gh=gfMul(gh,H);
  }
  // Length block: 0 (AAD len) || ct.length*8
  var lb=new Uint8Array(16);
  var bits=ct.length*8;
  lb[12]=(bits>>>24)&255;lb[13]=(bits>>>16)&255;lb[14]=(bits>>>8)&255;lb[15]=bits&255;
  for(var j=0;j<16;j++)gh[j]^=lb[j];
  gh=gfMul(gh,H);
  // Tag = GHASH XOR E(K, J0)
  var tag=aesBlock(J,w);
  for(var j=0;j<16;j++)tag[j]^=gh[j];
  // Output: IV || CT || TAG
  var out=new Uint8Array(12+ct.length+16);
  out.set(iv);out.set(ct,12);out.set(tag,12+ct.length);
  return out;
}

function gcmDec(data,keyBytes){
  if(data.length<28)return null;
  var iv=data.slice(0,12);
  var ct=data.slice(12,data.length-16);
  var tag=data.slice(data.length-16);
  var w=aesKeyExp(keyBytes);
  var H=aesBlock(new Uint8Array(16),w);
  var J=new Uint8Array(16);J.set(iv);J[15]=1;
  // Verify tag first
  var gh=new Uint8Array(16);
  for(var i=0;i<ct.length;i+=16){
    var blk=new Uint8Array(16);
    var end=Math.min(16,ct.length-i);
    for(var j=0;j<end;j++)blk[j]=ct[i+j];
    for(var j=0;j<16;j++)gh[j]^=blk[j];
    gh=gfMul(gh,H);
  }
  var lb=new Uint8Array(16);
  var bits=ct.length*8;
  lb[12]=(bits>>>24)&255;lb[13]=(bits>>>16)&255;lb[14]=(bits>>>8)&255;lb[15]=bits&255;
  for(var j=0;j<16;j++)gh[j]^=lb[j];
  gh=gfMul(gh,H);
  var expTag=aesBlock(J,w);
  for(var j=0;j<16;j++)expTag[j]^=gh[j];
  // Constant-time tag compare
  var diff=0;for(var j=0;j<16;j++)diff|=tag[j]^expTag[j];
  if(diff!==0)return null; // AUTH FAILED
  // Decrypt
  var pt=new Uint8Array(ct.length);
  var ctr=new Uint8Array(J);
  for(var i=0;i<ct.length;i+=16){
    ctr[15]++;if(ctr[15]===0){ctr[14]++;if(ctr[14]===0)ctr[13]++;}
    var ks=aesBlock(ctr,w);
    var end=Math.min(16,ct.length-i);
    for(var j=0;j<end;j++)pt[i+j]=ct[i+j]^ks[j];
  }
  return pt;
}

// Synchronous enc/dec replacing XOR
function enc(t,k){
  if(!aesKeyBytes)return btoa(t);
  var pt=new TextEncoder().encode(t);
  var iv=new Uint8Array(12);crypto.getRandomValues(iv);
  var r=gcmEnc(pt,aesKeyBytes,iv);
  var b='';for(var i=0;i<r.length;i++)b+=String.fromCharCode(r[i]);
  return btoa(b);
}

function dec(e,k){
  if(!aesKeyBytes)return '???';
  try{
    var raw=atob(e);
    var d=new Uint8Array(raw.length);
    for(var i=0;i<raw.length;i++)d[i]=raw.charCodeAt(i);
    var pt=gcmDec(d,aesKeyBytes);
    if(pt===null)return '\u26A0 Auth Failed';
    return new TextDecoder().decode(pt);
  }catch(x){return '???';}
}
