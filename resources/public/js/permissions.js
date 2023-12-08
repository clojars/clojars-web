const showHideNewJarInput = () => {
  const tb = $('#scope_to_jar_new');
  if ($('#scope_to_jar_select option:checked').val() === ':new') {
    tb.show();
  } else {
    tb.hide();
    tb.val('');
  }
};

$(() => {
  $('#scope_to_jar_select').on('change', showHideNewJarInput);
  showHideNewJarInput();
});
