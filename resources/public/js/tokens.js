const configureToggleTokenRows = (toggleSel, rowSel) => {
$(toggleSel).change(() => {
  if ($(toggleSel).is(':checked')) {
    $(rowSel).show();
  } else {
    $(rowSel).hide();
  }
});
};

const hideTokenRows = (sel) => $(sel).hide();

const enableAutoSetTokenName = () => {
  const single_use = $('#single_use');
  single_use.change(() => {
    const name_input = $('#name');
    if (single_use.is(':checked') &&
        !name_input.val()) {
      // drop timezone name
      const now = new Date().toString().substring(0, 33);
      name_input.val('<single-use ' + now + '>');
    }
  });
};

$(() => {
  hideTokenRows('.token-disabled');
  hideTokenRows('.token-used');
  hideTokenRows('.token-expired');
  configureToggleTokenRows('#show-disabled', '.token-disabled');
  configureToggleTokenRows('#show-used', '.token-used');
  configureToggleTokenRows('#show-expired', '.token-expired');
  enableAutoSetTokenName();
});
