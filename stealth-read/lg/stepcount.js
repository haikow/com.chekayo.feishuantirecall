(function(){
function whenSql(cb){var m=Process.findModuleByName('libsqlcipher.so');if(m){cb(m);return}var t=setInterval(function(){var mm=Process.findModuleByName('libsqlcipher.so');if(mm){clearInterval(t);cb(mm)}},100)}
whenSql(function(m){
  var step=m.getExportByName('sqlite3_step');
  var cnt=0;
  Interceptor.attach(step,{onEnter:function(a){cnt++}});
  send({ev:'armed'});
  setInterval(function(){send({ev:'HB', steps:cnt});},3000);
});
})();
