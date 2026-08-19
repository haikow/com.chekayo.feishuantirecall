(function(){
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var n=0;var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}if(++n>100){clearInterval(t);send({ev:'no_liblark'})}},50)}
whenLark(function(l){
  send({ev:'liblark', base:l.base.toString(16), size:l.size});
  var sc=Process.findModuleByName('libsqlcipher.so');
  send({ev:'sqlcipher', found:!!sc});
});
})();
